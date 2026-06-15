package dev.hivens.libnotify.windows

import dev.hivens.libnotify.DismissReason
import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.NotificationHandle
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierCapabilities
import dev.hivens.libnotify.NotifierConfig
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Windows notification backend on `Windows.UI.Notifications.ToastNotification`
 * via raw WinRT activation (see [WinRtBindings]). Pure Project Panama -- no WinRT
 * projection, no WIL/WRL, no C++/CX. The real system toast surface, not the
 * legacy `Shell_NotifyIcon` balloon.
 *
 * Flow per post: activate a `Windows.Data.Xml.Dom.XmlDocument`, `LoadXml` the
 * `ToastGeneric` payload ([ToastXml]), `CreateToastNotification` from it, stamp
 * the `Tag` (= the public id, used both for replace-in-place and to identify the
 * notification in activation callbacks), wire `add_Activated`/`add_Dismissed`
 * handlers, and `Show`.
 *
 * **AUMID.** Windows routes and de-duplicates toasts by Application User Model
 * ID. [NotifierConfig.appId] supplies it; a toast posted under an AUMID with no
 * registered Start-menu shortcut (or COM server) is silently dropped by the
 * shell. The backend constructs and shows the toast regardless -- it cannot
 * detect that the shell discarded it -- so a "nothing appears" symptom on
 * Windows almost always means an unregistered AUMID, not a libnotify fault.
 *
 * **Threading.** All WinRT calls run on a single [dispatchThread] that
 * `RoInitialize`s the MTA once and owns the notifier + factory for its life
 * (the [FreedesktopNotifier] task-queue pattern). MTA delivers activation
 * callbacks on system threadpool threads -- no message pump required -- so the
 * `Invoke` upcalls route by `Tag` through a process-wide concurrent map.
 *
 * **On-metal status.** The activation-callback path (synthesised COM handlers,
 * lenient QueryInterface) is implemented to the SDK ABI but has not yet been
 * verified on real Windows; show/replace/cancel are the load-bearing core.
 */
internal class ToastNotifier private constructor(
    private val bindings: WinRtBindings,
    private val config: NotifierConfig,
    override val capabilities: NotifierCapabilities,
) : Notifier {

    private val log = LoggerFactory.getLogger("libnotify.Toast")

    private val open = AtomicBoolean(true)
    private val handlers = CopyOnWriteArrayList<(NotificationEvent) -> Unit>()
    private val tasks = LinkedBlockingQueue<Runnable>()

    // Written by the dispatch thread at startup, read by it thereafter.
    @Volatile private var notifier: MemorySegment = MemorySegment.NULL
    @Volatile private var factory: MemorySegment = MemorySegment.NULL
    @Volatile private var creationOk = false
    private val readyLatch = CountDownLatch(1)

    /** publicId -> retained IToastNotification* (dispatch-thread-owned; held for Hide + replace). */
    private val deliveredToasts = HashMap<String, MemorySegment>()

    private val dispatchThread = Thread({ dispatchLoop() }, "libnotify-toast-${ProcessHandle.current().pid()}").apply {
        isDaemon = true
    }

    init {
        dispatchThread.start()
    }

    override val isOpen: Boolean get() = open.get()

    override fun notify(notification: Notification): NotificationHandle? {
        if (!open.get()) return null
        if (notification.actions.size > ToastXml.MAX_ACTIONS) {
            log.warn("Windows toasts allow {} actions; dropping {} extra(s)",
                ToastXml.MAX_ACTIONS, notification.actions.size - ToastXml.MAX_ACTIONS)
        }
        val future = CompletableFuture<NotificationHandle?>()
        tasks.add(Runnable { future.complete(runCatching { doNotify(notification) }.getOrNull()) })
        return runCatching { future.get(6, TimeUnit.SECONDS) }
            .getOrElse { log.warn("notify did not complete in time: {}", it.message); null }
    }

    override fun cancel(handle: NotificationHandle): Boolean {
        if (!open.get()) return false
        val future = CompletableFuture<Boolean>()
        tasks.add(Runnable { future.complete(runCatching { doCancel(handle) }.getOrDefault(false)) })
        return runCatching { future.get(6, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    override fun onEvent(handler: (NotificationEvent) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        dispatchThread.join(3_000)
    }

    fun awaitReady(): Boolean =
        runCatching { readyLatch.await(8, TimeUnit.SECONDS) && creationOk }.getOrDefault(false)

    // ── Dispatch thread ───────────────────────────────────────────────────────

    private fun dispatchLoop() {
        try {
            // RPC_E_CHANGED_MODE (0x80010106) means COM was already initialised
            // on this thread with a different model -- harmless, WinRT is usable.
            runCatching { bindings.handle("RoInitialize").invokeExact(WinRtBindings.RO_INIT_MULTITHREADED) as Int }
            if (!buildNotifierAndFactory()) {
                creationOk = false
                readyLatch.countDown()
                teardown()
                return
            }
            ensureHandlers(bindings)
            creationOk = true
            readyLatch.countDown()

            while (open.get()) {
                val task = tasks.poll(100, TimeUnit.MILLISECONDS) ?: continue
                runCatching { task.run() }.onFailure { log.warn("dispatch task threw: {}", it.message) }
            }
        } catch (t: Throwable) {
            log.warn("toast dispatch loop crashed: {}", t.message)
            if (readyLatch.count > 0) readyLatch.countDown()
        } finally {
            teardown()
        }
    }

    private fun buildNotifierAndFactory(): Boolean {
        Arena.ofConfined().use { call ->
            val aumid = config.appId ?: run {
                log.warn("NotifierConfig.appId (AUMID) is null; falling back to appName '{}'. " +
                    "Windows will drop toasts unless this id is registered.", config.appName)
                config.appName
            }
            val statics = activationFactory(call, WinRtBindings.CLASS_TOAST_MANAGER, WinRtBindings.IID_TOAST_MANAGER_STATICS)
                ?: run { log.warn("RoGetActivationFactory(ToastNotificationManager) failed"); return false }
            try {
                val aumidH = bindings.createHString(call, aumid)
                val out = call.allocate(ValueLayout.ADDRESS)
                val hr = bindings.downcall(
                    bindings.vtableFn(statics, WinRtBindings.IDX_CREATE_TOAST_NOTIFIER_WITH_ID),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                ).invokeExact(statics, aumidH, out) as Int
                bindings.deleteHString(aumidH)
                if (hr < 0) { log.warn("CreateToastNotifierWithId failed: 0x{}", Integer.toHexString(hr)); return false }
                notifier = out.get(ValueLayout.ADDRESS, 0)
            } finally {
                bindings.release(statics)
            }

            factory = activationFactory(call, WinRtBindings.CLASS_TOAST_NOTIFICATION, WinRtBindings.IID_TOAST_NOTIFICATION_FACTORY)
                ?: run { log.warn("RoGetActivationFactory(ToastNotification) failed"); return false }
            return notifier.address() != 0L && factory.address() != 0L
        }
    }

    private fun teardown() {
        synchronized(this) {
            for (toast in deliveredToasts.values) bindings.release(toast)
            deliveredToasts.clear()
        }
        bindings.release(notifier)
        bindings.release(factory)
        notifier = MemorySegment.NULL
        factory = MemorySegment.NULL
        runCatching { bindings.handle("RoUninitialize").invokeExact() as Unit }
        OWNER.entries.removeIf { it.value === this }
    }

    private fun activationFactory(call: Arena, className: String, iid: String): MemorySegment? {
        val classH = bindings.createHString(call, className)
        val out = call.allocate(ValueLayout.ADDRESS)
        val hr = bindings.handle("RoGetActivationFactory").invokeExact(classH, bindings.guid(iid), out) as Int
        bindings.deleteHString(classH)
        if (hr < 0) return null
        val p = out.get(ValueLayout.ADDRESS, 0)
        return if (p.address() == 0L) null else p
    }

    // ── Post / cancel (dispatch thread) ───────────────────────────────────────

    private fun doNotify(n: Notification): NotificationHandle? {
        Arena.ofConfined().use { call ->
            val imageUri = resolveImageUri(n.iconBytes ?: config.defaultIconBytes)
            val xml = ToastXml.build(n, imageUri)

            val xmlDoc = createXmlDocument(call, xml) ?: run { log.warn("XmlDocument LoadXml failed"); return null }
            try {
                val toast = createToast(call, xmlDoc) ?: run { log.warn("CreateToastNotification failed"); return null }
                val publicId = n.tag ?: nextAnonId()
                setTag(call, toast, publicId)
                wireHandlers(call, toast)

                val showFn = bindings.downcall(
                    bindings.vtableFn(notifier, WinRtBindings.IDX_SHOW),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                )
                val hr = showFn.invokeExact(notifier, toast) as Int
                if (hr < 0) {
                    log.warn("IToastNotifier.Show failed: 0x{}", Integer.toHexString(hr))
                    bindings.release(toast)
                    return null
                }

                deliveredToasts.put(publicId, toast)?.let { old ->
                    if (old.address() != toast.address()) bindings.release(old)
                }
                OWNER[publicId] = this
                return NotificationHandle(publicId)
            } finally {
                bindings.release(xmlDoc)
            }
        }
    }

    private fun doCancel(handle: NotificationHandle): Boolean {
        val toast = deliveredToasts[handle.id] ?: return false
        val hr = bindings.downcall(
            bindings.vtableFn(notifier, WinRtBindings.IDX_HIDE),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        ).invokeExact(notifier, toast) as Int
        deliveredToasts.remove(handle.id)?.let { bindings.release(it) }
        OWNER.remove(handle.id, this)
        return hr >= 0
    }

    private fun createXmlDocument(call: Arena, xml: String): MemorySegment? {
        val classH = bindings.createHString(call, WinRtBindings.CLASS_XML_DOCUMENT)
        val out = call.allocate(ValueLayout.ADDRESS)
        val hr = bindings.handle("RoActivateInstance").invokeExact(classH, out) as Int
        bindings.deleteHString(classH)
        if (hr < 0) return null
        val inspectable = out.get(ValueLayout.ADDRESS, 0)
        if (inspectable.address() == 0L) return null
        try {
            val docIo = bindings.queryInterface(inspectable, WinRtBindings.IID_XML_DOCUMENT_IO, call) ?: return null
            try {
                val xmlH = bindings.createHString(call, xml)
                val loadHr = bindings.downcall(
                    bindings.vtableFn(docIo, WinRtBindings.IDX_LOAD_XML),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                ).invokeExact(docIo, xmlH) as Int
                bindings.deleteHString(xmlH)
                if (loadHr < 0) return null
            } finally {
                bindings.release(docIo)
            }
            // CreateToastNotification wants the IXmlDocument face of the same object.
            return bindings.queryInterface(inspectable, WinRtBindings.IID_XML_DOCUMENT, call)
        } finally {
            bindings.release(inspectable)
        }
    }

    private fun createToast(call: Arena, xmlDoc: MemorySegment): MemorySegment? {
        val out = call.allocate(ValueLayout.ADDRESS)
        val hr = bindings.downcall(
            bindings.vtableFn(factory, WinRtBindings.IDX_CREATE_TOAST_NOTIFICATION),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        ).invokeExact(factory, xmlDoc, out) as Int
        if (hr < 0) return null
        val toast = out.get(ValueLayout.ADDRESS, 0)
        return if (toast.address() == 0L) null else toast
    }

    private fun setTag(call: Arena, toast: MemorySegment, publicId: String) {
        val t2 = bindings.queryInterface(toast, WinRtBindings.IID_TOAST_NOTIFICATION2, call) ?: run {
            log.warn("IToastNotification2 unavailable; replace + activation routing degraded for this toast")
            return
        }
        try {
            val tagH = bindings.createHString(call, publicId)
            val hr = bindings.downcall(
                bindings.vtableFn(t2, WinRtBindings.IDX_PUT_TAG),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ).invokeExact(t2, tagH) as Int
            bindings.deleteHString(tagH)
            if (hr < 0) log.warn("put_Tag failed (tag '{}' may exceed the 64-char limit): 0x{}", publicId, Integer.toHexString(hr))
        } finally {
            bindings.release(t2)
        }
    }

    private fun wireHandlers(call: Arena, toast: MemorySegment) {
        // EventRegistrationToken is a single int64; we never unsubscribe, so the
        // out token is written and ignored.
        val token = call.allocate(8)
        runCatching {
            bindings.downcall(
                bindings.vtableFn(toast, WinRtBindings.IDX_ADD_ACTIVATED),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ).invokeExact(toast, activatedHandler, token) as Int
        }.onFailure { log.warn("add_Activated failed: {}", it.message) }
        runCatching {
            bindings.downcall(
                bindings.vtableFn(toast, WinRtBindings.IDX_ADD_DISMISSED),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ).invokeExact(toast, dismissedHandler, token) as Int
        }.onFailure { log.warn("add_Dismissed failed: {}", it.message) }
    }

    // ── Called from the static Invoke upcalls (system callback thread) ────────

    private fun onActivated(publicId: String, arguments: String?) {
        val arg = arguments?.takeIf { it.isNotBlank() } ?: NotificationAction.DEFAULT_ID
        if (arg == NotificationAction.DEFAULT_ID) {
            fire(NotificationEvent.Activated(publicId))
        } else {
            fire(NotificationEvent.ActionInvoked(publicId, arg))
        }
    }

    private fun onDismissed(publicId: String, reasonCode: Int) {
        fire(NotificationEvent.Dismissed(publicId, mapReason(reasonCode)))
        OWNER.remove(publicId, this)
        // Release the kept toast on the dispatch thread (COM object lifecycle).
        tasks.add(Runnable { deliveredToasts.remove(publicId)?.let { bindings.release(it) } })
    }

    private fun fire(event: NotificationEvent) {
        for (h in handlers) {
            runCatching { h(event) }.onFailure { log.warn("onEvent handler threw: {}", it.message) }
        }
    }

    private fun nextAnonId(): String = "ln-${anonCounter.getAndIncrement()}"

    /** Write image bytes to a temp PNG and return its `file:///` URI, content-cached. Null if no bytes / on failure. */
    private fun resolveImageUri(bytes: ByteArray?): String? {
        if (bytes == null) return null
        val key = bytes.contentHashCode()
        IMAGE_CACHE[key]?.let { return it }
        return runCatching {
            val file: Path = Files.createTempFile("libnotify-", ".png")
            file.toFile().deleteOnExit()
            Files.write(file, bytes)
            val uri = file.toUri().toString()  // file:///C:/...
            IMAGE_CACHE[key] = uri
            uri
        }.getOrElse { log.warn("toast image temp-file write failed, posting without image: {}", it.message); null }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libnotify.Toast")
        private val anonCounter = AtomicLong(0)

        /** publicId -> owning notifier, for activation routing across instances. Concurrent: callbacks run off-thread. */
        private val OWNER = ConcurrentHashMap<String, ToastNotifier>()

        /** Content-hash -> file URI for toast images, so repeated posts of the same icon reuse one temp file. */
        private val IMAGE_CACHE = ConcurrentHashMap<Int, String>()

        @Volatile private var lastBindings: WinRtBindings? = null
        @Volatile private var activatedHandler: MemorySegment = MemorySegment.NULL
        @Volatile private var dismissedHandler: MemorySegment = MemorySegment.NULL

        fun create(config: NotifierConfig): Notifier? {
            val bindings = WinRtBindings.load() ?: run {
                log.info("combase.dll not loadable -- Windows toasts unavailable")
                return null
            }
            lastBindings = bindings
            val instance = ToastNotifier(bindings, config, capabilitiesFor())
            if (!instance.awaitReady()) {
                log.warn("Toast notifier failed to initialise (AUMID / WinRT activation); see prior warnings")
                instance.close()
                return null
            }
            return instance
        }

        private fun capabilitiesFor(): NotifierCapabilities = NotifierCapabilities(
            actions = true,
            maxActions = ToastXml.MAX_ACTIONS,
            bodyMarkup = false,
            icons = true,
            replace = true,
            closeEvents = true,
            urgency = true,
        )

        /** ToastDismissalReason -> DismissReason. */
        private fun mapReason(code: Int): DismissReason = when (code) {
            WinRtBindings.DISMISS_TIMED_OUT -> DismissReason.EXPIRED
            WinRtBindings.DISMISS_USER_CANCELED -> DismissReason.DISMISSED_BY_USER
            WinRtBindings.DISMISS_APPLICATION_HIDDEN -> DismissReason.CLOSED
            else -> DismissReason.UNKNOWN
        }

        @Synchronized
        private fun ensureHandlers(bindings: WinRtBindings) {
            if (activatedHandler.address() != 0L) return
            val lookup = MethodHandles.lookup()
            val invokeType = MethodType.methodType(
                Int::class.javaPrimitiveType!!,
                MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
            )
            val invokeDesc = FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            )
            val actStub = bindings.linker.upcallStub(
                lookup.findStatic(ToastNotifier::class.java, "activatedInvokeEntry", invokeType), invokeDesc, bindings.arena)
            val disStub = bindings.linker.upcallStub(
                lookup.findStatic(ToastNotifier::class.java, "dismissedInvokeEntry", invokeType), invokeDesc, bindings.arena)
            activatedHandler = bindings.makeEventHandler(actStub)
            dismissedHandler = bindings.makeEventHandler(disStub)
        }

        /** `ITypedEventHandler::Invoke` for activation. `HRESULT f(self, sender, args)`. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun activatedInvokeEntry(self: MemorySegment, sender: MemorySegment, args: MemorySegment): Int {
            runCatching {
                val b = lastBindings ?: return 0
                Arena.ofConfined().use { call ->
                    val publicId = readTag(b, call, sender) ?: return@use
                    val owner = OWNER[publicId] ?: return@use
                    val arguments = readHStringProperty(b, call, args, WinRtBindings.IID_TOAST_ACTIVATED_ARGS, WinRtBindings.IDX_GET_ARGUMENTS)
                    owner.onActivated(publicId, arguments)
                }
            }.onFailure { log.warn("activated callback threw: {}", it.message) }
            return 0  // S_OK
        }

        /** `ITypedEventHandler::Invoke` for dismissal. `HRESULT f(self, sender, args)`. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun dismissedInvokeEntry(self: MemorySegment, sender: MemorySegment, args: MemorySegment): Int {
            runCatching {
                val b = lastBindings ?: return 0
                Arena.ofConfined().use { call ->
                    val publicId = readTag(b, call, sender) ?: return@use
                    val owner = OWNER[publicId] ?: return@use
                    owner.onDismissed(publicId, readReason(b, call, args))
                }
            }.onFailure { log.warn("dismissed callback threw: {}", it.message) }
            return 0  // S_OK
        }

        /** Read the toast's Tag (= our public id) off the sender via IToastNotification2.get_Tag. */
        private fun readTag(b: WinRtBindings, call: Arena, sender: MemorySegment): String? {
            return readHStringProperty(b, call, sender, WinRtBindings.IID_TOAST_NOTIFICATION2, WinRtBindings.IDX_GET_TAG)
                ?.takeIf { it.isNotEmpty() }
        }

        /** QI [obj] for [iid] and read an HSTRING-returning property at [index]. */
        private fun readHStringProperty(b: WinRtBindings, call: Arena, obj: MemorySegment, iid: String, index: Int): String? {
            val face = b.queryInterface(obj, iid, call) ?: return null
            try {
                val out = call.allocate(ValueLayout.ADDRESS)
                val hr = b.downcall(
                    b.vtableFn(face, index),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                ).invokeExact(face, out) as Int
                if (hr < 0) return null
                val h = out.get(ValueLayout.ADDRESS, 0)
                val s = b.readHString(call, h)
                b.deleteHString(h)
                return s
            } finally {
                b.release(face)
            }
        }

        /** QI args for IToastDismissedEventArgs and read get_Reason (an int out-param). */
        private fun readReason(b: WinRtBindings, call: Arena, args: MemorySegment): Int {
            val face = b.queryInterface(args, WinRtBindings.IID_TOAST_DISMISSED_ARGS, call) ?: return -1
            try {
                val out = call.allocate(ValueLayout.JAVA_INT)
                val hr = b.downcall(
                    b.vtableFn(face, WinRtBindings.IDX_GET_REASON),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                ).invokeExact(face, out) as Int
                return if (hr < 0) -1 else out.get(ValueLayout.JAVA_INT, 0)
            } finally {
                b.release(face)
            }
        }
    }
}
