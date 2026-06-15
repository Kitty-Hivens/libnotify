package dev.hivens.libnotify.macos

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.NotificationHandle
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierCapabilities
import dev.hivens.libnotify.NotifierConfig
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * macOS notification backend on `NSUserNotificationCenter` via the Objective-C
 * runtime (see [ObjcBindings]). Project Panama only -- no JNI, no Swift, no
 * Foundation bridging dependency.
 *
 * **Why NSUserNotification.** It is the one notification API that works from a
 * plain `java ...` process -- no signed `.app` bundle, no entitlements -- which
 * is the realistic JVM-on-desktop case (the same bundle-free stance libtray's
 * Accessory-policy tray takes). Its successor `UNUserNotificationCenter` is the
 * supported API but `[UNUserNotificationCenter currentNotificationCenter]`
 * returns nil outside a bundle, so it cannot stand alone here. [create] detects
 * a bundle-with-UN environment and logs it; the modern path is a follow-up
 * (it needs a signable bundle to develop and verify against, plus the ObjC
 * block ABI for its authorization + delegate completion handlers).
 * `NSUserNotification` is deprecated since macOS 11 but remains functional
 * through current releases.
 *
 * **Threading + run loop.** `NSUserNotificationCenter` is callable from any
 * thread, but the delivered banners and the activation delegate are serviced by
 * the Cocoa main run loop. A host UI toolkit (Compose Desktop / Skiko, JavaFX)
 * runs that loop already; a headless process must run `[NSApp run]` on the
 * `-XstartOnFirstThread` main thread (the smoke harness does) or activation
 * events never fire.
 */
internal class NsUserNotificationNotifier private constructor(
    private val bindings: ObjcBindings,
    private val center: MemorySegment,
    override val capabilities: NotifierCapabilities,
) : Notifier {

    private val log = LoggerFactory.getLogger("libnotify.NSUserNotification")

    private val open = AtomicBoolean(true)
    private val handlers = CopyOnWriteArrayList<(NotificationEvent) -> Unit>()

    // Concurrent because the activation delegate ([handleActivation]) reads these
    // on the Cocoa main thread while notify/cancel/close mutate them under
    // synchronized(this); plain HashMaps could be seen mid-rehash from the
    // lock-free read and corrupt or hang.
    /** publicId -> retained NSUserNotification* (held so cancel/replace can remove + release it). */
    private val delivered = ConcurrentHashMap<String, MemorySegment>()

    /** publicId -> the first action's id, recovered when the main action button (activationType 2) fires. */
    private val firstActionId = ConcurrentHashMap<String, String>()

    override val isOpen: Boolean get() = open.get()

    override fun notify(notification: Notification): NotificationHandle? {
        if (!open.get()) return null
        return runCatching { synchronized(this) { deliver(notification) } }
            .getOrElse { log.warn("notify threw: {}", it.message); null }
    }

    override fun cancel(handle: NotificationHandle): Boolean {
        if (!open.get()) return false
        return runCatching { synchronized(this) { removeDelivered(handle.id) } }
            .getOrElse { log.warn("cancel threw: {}", it.message); false }
    }

    override fun onEvent(handler: (NotificationEvent) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        REGISTRY.remove(this)
        synchronized(this) {
            // The delegate is a process-shared singleton on the singleton
            // center; only drop it when no notifier is left, or closing one
            // would silence activation callbacks for every other open one.
            if (REGISTRY.isEmpty()) {
                runCatching {
                    bindings.handle("objc_msgSend_void_id")
                        .invokeExact(center, bindings.sel("setDelegate:"), MemorySegment.NULL) as Unit
                }
            }
            for (note in delivered.values) {
                runCatching { bindings.handle("objc_release").invokeExact(note) as Unit }
            }
            delivered.clear()
            firstActionId.clear()
        }
    }

    // ── Posting ──────────────────────────────────────────────────────────────

    private fun deliver(n: Notification): NotificationHandle? {
        return autoreleasepool {
            val noteCls = bindings.cls("NSUserNotification")
            val alloc = msgId(noteCls, "alloc")
            val note = msgId(alloc, "init")
            if (note.address() == 0L) return@autoreleasepool null

            voidId(note, "setTitle:", bindings.nsString(n.title))
            if (n.body.isNotEmpty()) voidId(note, "setInformativeText:", bindings.nsString(n.body))

            val publicId = n.tag ?: nextAnonId()
            // Identifier drives replace-in-place: delivering a second
            // notification with the same identifier swaps the banner (10.9+).
            voidId(note, "setIdentifier:", bindings.nsString(publicId))

            applyIcon(note, n.iconBytes ?: configIconBytes)
            applyActions(note, n, publicId)

            // Show even when our app is frontmost -- the shouldPresent
            // delegate returns YES, but only takes effect once a delegate is
            // set, which we did at create().
            voidId(center, "deliverNotification:", note)

            // We own the +1 from alloc/init; keep it for cancel/replace.
            delivered.put(publicId, note)?.let { old ->
                if (old.address() != note.address()) {
                    runCatching { bindings.handle("objc_release").invokeExact(old) as Unit }
                }
            }
            NotificationHandle(publicId)
        }
    }

    private fun applyIcon(note: MemorySegment, bytes: ByteArray?) {
        if (bytes == null) return
        runCatching {
            val nsData = bindings.nsData(bytes)
            val imgCls = bindings.cls("NSImage")
            val img = bindings.handle("objc_msgSend_id_id")
                .invokeExact(msgId(imgCls, "alloc"), bindings.sel("initWithData:"), nsData) as MemorySegment
            if (img.address() != 0L) {
                voidId(note, "setContentImage:", img)
                // setContentImage: retains; drop our alloc +1.
                bindings.handle("objc_release").invokeExact(img) as Unit
            }
        }.onFailure { log.warn("icon decode/attach failed, posting without image: {}", it.message) }
    }

    private fun applyActions(note: MemorySegment, n: Notification, publicId: String) {
        if (n.actions.isEmpty()) {
            voidLong(note, "setHasActionButton:", 0L)
            return
        }
        val first = n.actions.first()
        voidLong(note, "setHasActionButton:", 1L)
        voidId(note, "setActionButtonTitle:", bindings.nsString(first.label))
        firstActionId[publicId] = first.id

        val extras = n.actions.drop(1)
        if (extras.isEmpty()) return
        // Remaining actions land in the alternate-action menu (10.10+):
        // NSArray<NSUserNotificationAction*>, each actionWithIdentifier:title:.
        runCatching {
            val actionCls = bindings.cls("NSUserNotificationAction")
            val actionSel = bindings.sel("actionWithIdentifier:title:")
            val segs = extras.map { a ->
                bindings.handle("objc_msgSend_id_id_id").invokeExact(
                    actionCls, actionSel, bindings.nsString(a.id), bindings.nsString(a.label),
                ) as MemorySegment
            }
            Arena.ofConfined().use { tmp ->
                val arr = tmp.allocate(ValueLayout.ADDRESS, segs.size.toLong())
                segs.forEachIndexed { i, seg -> arr.setAtIndex(ValueLayout.ADDRESS, i.toLong(), seg) }
                val nsArray = bindings.handle("objc_msgSend_id_ptr_long").invokeExact(
                    bindings.cls("NSArray"), bindings.sel("arrayWithObjects:count:"), arr, segs.size.toLong(),
                ) as MemorySegment
                voidId(note, "setAdditionalActions:", nsArray)
            }
        }.onFailure { log.warn("additional actions unavailable on this macOS, dropping extras: {}", it.message) }
    }

    private fun removeDelivered(publicId: String): Boolean {
        val note = delivered.remove(publicId) ?: return false
        firstActionId.remove(publicId)
        autoreleasepool {
            voidId(center, "removeDeliveredNotification:", note)
        }
        runCatching { bindings.handle("objc_release").invokeExact(note) as Unit }
        return true
    }

    // ── Activation routing (called from the static delegate upcall) ──────────

    private fun handleActivation(note: MemorySegment) {
        val publicId = bindings.jvmString(msgId(note, "identifier")) ?: return
        if (!delivered.containsKey(publicId)) return  // not ours
        when ((bindings.handle("objc_msgSend_long").invokeExact(note, bindings.sel("activationType")) as Long).toInt()) {
            ACTIVATION_CONTENTS_CLICKED -> fire(NotificationEvent.Activated(publicId))
            ACTIVATION_ACTION_BUTTON -> firstActionId[publicId]?.let {
                fire(NotificationEvent.ActionInvoked(publicId, it))
            }
            ACTIVATION_ADDITIONAL_ACTION -> {
                val action = msgId(note, "additionalActivationAction")
                bindings.jvmString(msgId(action, "identifier"))?.let {
                    fire(NotificationEvent.ActionInvoked(publicId, it))
                }
            }
            else -> Unit  // None / Replied -- nothing to surface
        }
    }

    private fun fire(event: NotificationEvent) {
        for (h in handlers) {
            runCatching { h(event) }.onFailure { log.warn("onEvent handler threw: {}", it.message) }
        }
    }

    // ── msgSend conveniences ─────────────────────────────────────────────────

    private fun msgId(receiver: MemorySegment, selector: String): MemorySegment =
        bindings.handle("objc_msgSend_id").invokeExact(receiver, bindings.sel(selector)) as MemorySegment

    private fun voidId(receiver: MemorySegment, selector: String, arg: MemorySegment) {
        bindings.handle("objc_msgSend_void_id").invokeExact(receiver, bindings.sel(selector), arg) as Unit
    }

    private fun voidLong(receiver: MemorySegment, selector: String, arg: Long) {
        bindings.handle("objc_msgSend_void_long").invokeExact(receiver, bindings.sel(selector), arg) as Unit
    }

    private inline fun <T> autoreleasepool(block: () -> T): T {
        val pool = bindings.handle("objc_autoreleasePoolPush").invokeExact() as MemorySegment
        try {
            return block()
        } finally {
            runCatching { bindings.handle("objc_autoreleasePoolPop").invokeExact(pool) as Unit }
        }
    }

    /** Per-notifier fallback icon, captured from the config at construction time. */
    private var configIconBytes: ByteArray? = null

    internal companion object {
        private val log = LoggerFactory.getLogger("libnotify.NSUserNotification")
        private val anonCounter = AtomicLong(0)

        private const val ACTIVATION_CONTENTS_CLICKED = 1
        private const val ACTIVATION_ACTION_BUTTON = 2
        private const val ACTIVATION_ADDITIONAL_ACTION = 4

        /** Live notifiers, walked by the shared delegate upcall to find the owner of an activated notification. */
        private val REGISTRY = CopyOnWriteArrayList<NsUserNotificationNotifier>()

        // The delegate class + its upcall stubs are built once per JVM and held
        // for the process lifetime (the OS may call the delegate at any time).
        @Volatile private var delegateInstance: MemorySegment = MemorySegment.NULL
        @Volatile private var stubArena: Arena? = null
        @Volatile private var lastBindings: ObjcBindings? = null

        private fun nextAnonId(): String = "ln-${anonCounter.getAndIncrement()}"

        /**
         * `-userNotificationCenter:didActivateNotification:` IMP. ObjC calls it
         * as `void f(id self, SEL _cmd, id center, id notification)`. Routes the
         * activation to whichever live notifier owns the notification.
         */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun didActivateEntry(self: MemorySegment, cmd: MemorySegment, center: MemorySegment, note: MemorySegment) {
            for (inst in REGISTRY) {
                runCatching { inst.handleActivation(note) }
                    .onFailure { log.warn("didActivate routing threw: {}", it.message) }
            }
        }

        /**
         * `-userNotificationCenter:shouldPresentNotification:` IMP, returning
         * BOOL. We always return YES so banners show even when our process is
         * frontmost (the default is NO in that case).
         */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun shouldPresentEntry(self: MemorySegment, cmd: MemorySegment, center: MemorySegment, note: MemorySegment): Boolean = true

        fun create(config: NotifierConfig): Notifier? {
            val bindings = ObjcBindings.load() ?: run {
                log.info("Objective-C runtime / Foundation not loadable -- macOS notifications unavailable")
                return null
            }
            lastBindings = bindings

            val centerCls = bindings.clsOrNull("NSUserNotificationCenter") ?: run {
                log.info("NSUserNotificationCenter not available -- macOS notifications unavailable")
                return null
            }
            val center = bindings.handle("objc_msgSend_id")
                .invokeExact(centerCls, bindings.sel("defaultUserNotificationCenter")) as MemorySegment
            if (center.address() == 0L) {
                log.info("defaultUserNotificationCenter returned NULL -- macOS notifications unavailable")
                return null
            }

            if (detectBundledUnEnvironment(bindings)) {
                log.info(
                    "Process is a bundle with UNUserNotificationCenter available. libnotify uses the bundle-free " +
                        "NSUserNotification path; the modern UNUserNotificationCenter backend is a planned follow-up.",
                )
            }

            ensureDelegateClass(bindings)

            val instance = NsUserNotificationNotifier(bindings, center, capabilitiesFor())
            instance.configIconBytes = config.defaultIconBytes
            REGISTRY.add(instance)
            // One delegate object is shared across notifiers (the center is a
            // process singleton); the last creator installs it, the upcall
            // routes by notification identity.
            if (delegateInstance.address() != 0L) {
                runCatching {
                    bindings.handle("objc_msgSend_void_id")
                        .invokeExact(center, bindings.sel("setDelegate:"), delegateInstance) as Unit
                }
            }
            return instance
        }

        /** Fixed capabilities of the NSUserNotification surface. */
        private fun capabilitiesFor(): NotifierCapabilities = NotifierCapabilities(
            actions = true,
            // One prominent action button plus an alternate-action menu for the
            // rest; not a hard ceiling, but only the first reads as a button.
            maxActions = Int.MAX_VALUE,
            bodyMarkup = false,
            icons = true,
            replace = true,
            // NSUserNotification exposes no reliable dismissal callback.
            closeEvents = false,
            urgency = false,
        )

        /**
         * True when the process is a real bundle (has a bundle identifier) and
         * the modern `UNUserNotificationCenter` class is present -- the
         * environment where the supported UN API would work. Used only for an
         * informational log today.
         */
        private fun detectBundledUnEnvironment(bindings: ObjcBindings): Boolean {
            return runCatching {
                val bundleCls = bindings.clsOrNull("NSBundle") ?: return false
                val mainBundle = bindings.handle("objc_msgSend_id")
                    .invokeExact(bundleCls, bindings.sel("mainBundle")) as MemorySegment
                if (mainBundle.address() == 0L) return false
                val bid = bindings.handle("objc_msgSend_id")
                    .invokeExact(mainBundle, bindings.sel("bundleIdentifier")) as MemorySegment
                val bundled = bid.address() != 0L
                bundled && bindings.clsOrNull("UNUserNotificationCenter") != null
            }.getOrDefault(false)
        }

        /**
         * Build the runtime `LibnotifyNSUNDelegate_<pid>` class once per JVM:
         * subclass NSObject, add the two `NSUserNotificationCenterDelegate`
         * methods mapped to Panama upcall stubs, register, instantiate.
         */
        @Synchronized
        private fun ensureDelegateClass(bindings: ObjcBindings) {
            if (delegateInstance.address() != 0L) return

            val arena = Arena.ofShared()
            val linker = Linker.nativeLinker()

            val didActivateHandle = MethodHandles.lookup().findStatic(
                NsUserNotificationNotifier::class.java, "didActivateEntry",
                MethodType.methodType(
                    Void.TYPE,
                    MemorySegment::class.java, MemorySegment::class.java,
                    MemorySegment::class.java, MemorySegment::class.java,
                ),
            )
            val didActivateStub = linker.upcallStub(
                didActivateHandle,
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ),
                arena,
            )

            val shouldPresentHandle = MethodHandles.lookup().findStatic(
                NsUserNotificationNotifier::class.java, "shouldPresentEntry",
                MethodType.methodType(
                    Boolean::class.javaPrimitiveType!!,
                    MemorySegment::class.java, MemorySegment::class.java,
                    MemorySegment::class.java, MemorySegment::class.java,
                ),
            )
            val shouldPresentStub = linker.upcallStub(
                shouldPresentHandle,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_BOOLEAN,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ),
                arena,
            )

            val nsObjectCls = bindings.cls("NSObject")
            val className = "LibnotifyNSUNDelegate_${ProcessHandle.current().pid()}"
            val newClass = bindings.handle("objc_allocateClassPair")
                .invokeExact(nsObjectCls, arena.allocateFrom(className), 0L) as MemorySegment
            require(newClass.address() != 0L) { "objc_allocateClassPair failed for $className" }

            // Type encodings: "v@:@@" = void (id self, SEL, id, id);
            //                 "c@:@@" = BOOL (signed char) return, same args.
            bindings.handle("class_addMethod").invokeExact(
                newClass, bindings.sel("userNotificationCenter:didActivateNotification:"),
                didActivateStub, arena.allocateFrom("v@:@@"),
            ) as Boolean
            bindings.handle("class_addMethod").invokeExact(
                newClass, bindings.sel("userNotificationCenter:shouldPresentNotification:"),
                shouldPresentStub, arena.allocateFrom("c@:@@"),
            ) as Boolean
            bindings.handle("objc_registerClassPair").invokeExact(newClass) as Unit

            val allocated = bindings.handle("class_createInstance").invokeExact(newClass, 0L) as MemorySegment
            val initialised = bindings.handle("objc_msgSend_id")
                .invokeExact(allocated, bindings.sel("init")) as MemorySegment
            delegateInstance = bindings.handle("objc_retain").invokeExact(initialised) as MemorySegment
            stubArena = arena
            log.info("Registered runtime ObjC delegate class {} for NSUserNotificationCenter", className)
        }
    }
}
