package dev.hivens.libnotify.linux

import dev.hivens.libnotify.DEFAULT_ACTION_ID
import dev.hivens.libnotify.DismissReason
import dev.hivens.libnotify.DispatchCalls
import dev.hivens.libnotify.EventListeners
import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.NotificationHandle
import dev.hivens.libnotify.NotificationListener
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierCapabilities
import dev.hivens.libnotify.NotifierConfig
import dev.hivens.libnotify.Subscription
import dev.hivens.libnotify.Timeout
import dev.hivens.libnotify.Urgency
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * Linux notification backend -- `org.freedesktop.Notifications` over the D-Bus
 * session bus, the protocol every desktop notification daemon implements
 * (Dunst, Mako, Fnott, Plasma, GNOME Shell, Xfce). Pure libdbus via Project
 * Panama (see [DBusBindings]); no GLib / libnotify-C / GTK transitive
 * dependency.
 *
 * The backend is a *client*: it sends `Notify` / `CloseNotification` /
 * `GetCapabilities` method calls and subscribes to the `ActionInvoked` and
 * `NotificationClosed` signals. There is no bus name to own and no Panama
 * upcall stub -- signals are pulled off the connection on a thread we control.
 *
 * **Single-thread ownership.** libdbus connections are not freely
 * thread-safe to share, so every call against [connection] after construction
 * runs on one [dispatchThread], which also frees the connection when it exits.
 * Public API methods ([notify], [cancel]) hand their work over through [calls] and
 * wait for the answer. That keeps the native call off the caller's thread, but the
 * caller still blocks while it waits, so a hung daemon holds it for seconds.
 *
 * **Bus loss.** When the session bus goes away the next call opens a new
 * connection, so a restarted bus needs nothing from the host application. What
 * belonged to the old bus is dropped with it: notifications posted before no
 * longer report events and cannot be cancelled.
 */
internal class FreedesktopNotifier private constructor(
    private val bindings: DBusBindings,
    initialConnection: MemorySegment,
    private val config: NotifierConfig,
    override val capabilities: NotifierCapabilities,
    advertisedBodyMarkup: Boolean?,
) : Notifier {

    private val log = LoggerFactory.getLogger("libnotify.Freedesktop")

    private val open = AtomicBoolean(true)
    private val listeners = EventListeners(log)

    /** Work from [notify] / [cancel], run on the [dispatchThread]. */
    private val calls = DispatchCalls(log)

    // These maps are touched ONLY on the dispatch thread (doNotify, doCancel,
    // dispatchSignal all run there), so plain HashMaps are safe -- no
    // concurrent access despite the public API being callable from any thread.
    private val publicToServer = HashMap<String, Long>()
    private val serverToPublic = HashMap<Long, String>()
    private var anonCounter = 0L

    // Null until a server has answered GetCapabilities. Dispatch-thread-owned
    // once that thread starts, same as the maps above.
    private var bodyMarkup: Boolean? = advertisedBodyMarkup

    // Replaced by ensureConnected when the bus drops. Dispatch-thread-owned.
    private var connection: MemorySegment = initialConnection

    private val dispatchThread = Thread({ dispatchLoop() }, "libnotify-fdo-${ProcessHandle.current().pid()}").apply {
        isDaemon = true
    }

    init {
        calls.ownedBy(dispatchThread)
        dispatchThread.start()
    }

    override val isOpen: Boolean get() = open.get()

    override fun notify(notification: Notification): NotificationHandle? {
        if (!open.get()) return null
        return calls.call("Notify", null, QUEUE_WAIT_MS, WORK_CAP_MS) { doNotify(notification) }
    }

    override fun cancel(handle: NotificationHandle): Boolean {
        if (!open.get()) return false
        return calls.call("CloseNotification", false, QUEUE_WAIT_MS, WORK_CAP_MS) { doCancel(handle) }
    }

    override fun onEvent(listener: NotificationListener): Subscription = listeners.add(listener)

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        calls.shutdown()
        // Called from an event handler: the loop exits and frees everything once
        // that handler returns, and joining here would only wait on ourselves.
        if (Thread.currentThread() === dispatchThread) return
        // Don't hard-interrupt: a half-finished native send/flush could leave the
        // connection in a bad state.
        dispatchThread.join(JOIN_TIMEOUT_MS)
        if (dispatchThread.isAlive) {
            log.warn(
                "libnotify-fdo still busy after {} ms, it frees the D-Bus connection when its current call returns",
                JOIN_TIMEOUT_MS,
            )
        }
    }

    // ── Dispatch loop (the only thread that touches the connection) ───────────

    private fun dispatchLoop() {
        val readWrite = bindings.handle("dbus_connection_read_write")
        val popMessage = bindings.handle("dbus_connection_pop_message")
        val unref = bindings.handle("dbus_message_unref")
        try {
            while (open.get()) {
                try {
                    // Run queued API work first so notify/cancel latency is just
                    // the bus round-trip, not a poll interval. The wait below is
                    // what makes that true: the queue wakes this thread the moment
                    // work is offered, whatever the timeout says.
                    calls.runQueued(SIGNAL_POLL_INTERVAL_MS)
                    // Non-blocking pump of incoming signals.
                    readWrite.invokeExact(connection, 0) as Int
                    while (open.get()) {
                        val msg = popMessage.invokeExact(connection) as MemorySegment
                        if (msg.address() == 0L) break
                        try {
                            dispatchSignal(msg)
                        } catch (t: Throwable) {
                            log.warn("signal dispatch threw, dropping message: {}", t.message)
                        } finally {
                            runCatching { unref.invokeExact(msg) as Unit }
                        }
                    }
                } catch (t: Throwable) {
                    log.warn("D-Bus dispatch iteration threw: {}", t.message)
                    Thread.sleep(200)  // don't burn CPU on a permanently broken bus
                }
            }
        } finally {
            open.set(false)
            calls.shutdown()
            releaseConnection()
        }
    }

    /**
     * Runs on the dispatch thread as it exits. Nothing else touches the connection
     * after construction, so there is no live dbus_* call to free memory under,
     * however long the last one took.
     */
    private fun releaseConnection() {
        closeConnection(bindings, connection)
        // The shared arena holds the library lookup and downcall handles. This
        // client backend uses no upcall stubs, so nothing outlives it.
        runCatching { bindings.arena.close() }
    }

    /**
     * Dispatch thread only. Replaces a connection whose bus went away, and says
     * whether there is a live one to use. The ids, routes and capabilities all
     * came from the server on the old bus, so they go with it.
     */
    private fun ensureConnected(): Boolean {
        if ((bindings.handle("dbus_connection_get_is_connected").invokeExact(connection) as Int) != 0) return true
        val fresh = connectSession(bindings) ?: return false
        closeConnection(bindings, connection)
        connection = fresh
        publicToServer.clear()
        serverToPublic.clear()
        bodyMarkup = null
        log.info("reconnected to the session bus")
        return true
    }

    // ── Notify ───────────────────────────────────────────────────────────────

    private fun doNotify(n: Notification): NotificationHandle? {
        if (!ensureConnected()) return null
        Arena.ofConfined().use { call ->
            val body = if (serverParsesBodyMarkup(call)) escapeBodyMarkup(n.body) else n.body
            val msg = newCall(call, "Notify") ?: return null
            try {
                val iter = call.allocate(bindings.messageIterLayout)
                bindings.handle("dbus_message_iter_init_append").invokeExact(msg, iter) as Unit

                bindings.appendString(call, iter, DBusBindings.DBUS_TYPE_STRING, config.appName)
                // replaces_id: reuse the live server id for this tag, so the
                // daemon swaps the banner in place instead of stacking a new one.
                val replacesId = n.tag?.let { publicToServer[it] }?.toInt() ?: 0
                bindings.appendUint32(call, iter, replacesId)
                bindings.appendString(call, iter, DBusBindings.DBUS_TYPE_STRING,
                    n.iconName ?: config.defaultIconName ?: "")
                bindings.appendString(call, iter, DBusBindings.DBUS_TYPE_STRING, n.title)
                bindings.appendString(call, iter, DBusBindings.DBUS_TYPE_STRING, body)

                // actions: as = [id, label, id, label, ...]. The reserved
                // "default" action (empty label = not drawn as a button) makes a
                // plain body click surface as ActionInvoked("default"), which we
                // translate to NotificationEvent.Activated.
                val actSig = call.allocateUtf8("s")
                val actArr = call.allocate(bindings.messageIterLayout)
                bindings.openContainer(iter, DBusBindings.DBUS_TYPE_ARRAY, actSig, actArr)
                bindings.appendString(call, actArr, DBusBindings.DBUS_TYPE_STRING, DEFAULT_ACTION_ID)
                bindings.appendString(call, actArr, DBusBindings.DBUS_TYPE_STRING, "")
                for (a in n.actions) {
                    bindings.appendString(call, actArr, DBusBindings.DBUS_TYPE_STRING, a.id)
                    bindings.appendString(call, actArr, DBusBindings.DBUS_TYPE_STRING, a.label)
                }
                bindings.closeContainer(iter, actArr)

                // hints: a{sv}
                val hintSig = call.allocateUtf8("{sv}")
                val hints = call.allocate(bindings.messageIterLayout)
                bindings.openContainer(iter, DBusBindings.DBUS_TYPE_ARRAY, hintSig, hints)
                bindings.appendDictByte(call, hints, "urgency", urgencyByte(n.urgency))
                n.category?.let { bindings.appendDictString(call, hints, "category", it) }
                config.appId?.let { bindings.appendDictString(call, hints, "desktop-entry", it) }
                decodeRgba(n.iconBytes ?: config.defaultIconBytes)?.let {
                    bindings.appendDictImageData(call, hints, "image-data", it)
                }
                bindings.closeContainer(iter, hints)

                // expire_timeout: i
                bindings.appendInt32(call, iter, expireMillis(n.timeout))

                val serverId = sendAndReadUint32(call, msg) ?: return null
                val publicId = n.tag ?: nextAnonId()
                // Drop any prior mapping under this public id (replace case).
                publicToServer[publicId]?.let { serverToPublic.remove(it) }
                val key = serverId.toLong() and 0xFFFF_FFFFL
                publicToServer[publicId] = key
                serverToPublic[key] = publicId
                return NotificationHandle(publicId)
            } finally {
                runCatching { bindings.handle("dbus_message_unref").invokeExact(msg) as Unit }
            }
        }
    }

    private fun doCancel(handle: NotificationHandle): Boolean {
        if (!ensureConnected()) return false
        val serverId = publicToServer[handle.id] ?: return false
        Arena.ofConfined().use { call ->
            val msg = newCall(call, "CloseNotification") ?: return false
            try {
                val iter = call.allocate(bindings.messageIterLayout)
                bindings.handle("dbus_message_iter_init_append").invokeExact(msg, iter) as Unit
                bindings.appendUint32(call, iter, serverId.toInt())
                val error = call.allocate(bindings.errorLayout)
                bindings.handle("dbus_error_init").invokeExact(error) as Unit
                val reply = bindings.handle("dbus_connection_send_with_reply_and_block")
                    .invokeExact(connection, msg, REPLY_TIMEOUT_MS, error) as MemorySegment
                // A NULL reply is the daemon not answering -- absent, restarted
                // mid-call, or past the timeout. Reporting success there told a
                // caller the banner was taken down when nothing had heard the
                // request, and Notifier.cancel is documented as returning true
                // only when the backend accepted it.
                val delivered = reply.address() != 0L
                if (delivered) {
                    runCatching { bindings.handle("dbus_message_unref").invokeExact(reply) as Unit }
                } else {
                    log.warn("CloseNotification got no reply (server absent or slow)")
                }
                freeErrorIfSet(error)
                // On success the server also answers with a NotificationClosed
                // signal, which our pump turns into Dismissed(CLOSED) and uses
                // to clear the maps.
                return delivered
            } finally {
                runCatching { bindings.handle("dbus_message_unref").invokeExact(msg) as Unit }
            }
        }
    }

    /** Send [msg] with a blocking reply and read a single uint32 out of it (the Notify id). */
    private fun sendAndReadUint32(call: Arena, msg: MemorySegment): Int? {
        val error = call.allocate(bindings.errorLayout)
        bindings.handle("dbus_error_init").invokeExact(error) as Unit
        val reply = bindings.handle("dbus_connection_send_with_reply_and_block")
            .invokeExact(connection, msg, REPLY_TIMEOUT_MS, error) as MemorySegment
        if (reply.address() == 0L) {
            log.warn("Notify got no reply (server absent or slow)")
            freeErrorIfSet(error)
            return null
        }
        try {
            val rIter = call.allocate(bindings.messageIterLayout)
            if ((bindings.handle("dbus_message_iter_init").invokeExact(reply, rIter) as Int) == 0) return null
            return bindings.readUint32(call, rIter)
        } finally {
            runCatching { bindings.handle("dbus_message_unref").invokeExact(reply) as Unit }
        }
    }

    // ── Incoming signals ─────────────────────────────────────────────────────

    private fun dispatchSignal(msg: MemorySegment) {
        if ((bindings.handle("dbus_message_get_type").invokeExact(msg) as Int) != DBusBindings.DBUS_MESSAGE_TYPE_SIGNAL) return
        val iface = bindings.readMessageString("dbus_message_get_interface", msg) ?: return
        if (iface != IFACE) return
        val member = bindings.readMessageString("dbus_message_get_member", msg) ?: return

        Arena.ofConfined().use { call ->
            val iter = call.allocate(bindings.messageIterLayout)
            if ((bindings.handle("dbus_message_iter_init").invokeExact(msg, iter) as Int) == 0) return
            when (member) {
                "ActionInvoked" -> {
                    val serverId = bindings.readUint32(call, iter) ?: return
                    bindings.handle("dbus_message_iter_next").invokeExact(iter) as Int
                    val key = bindings.readString(call, iter) ?: return
                    val publicId = serverToPublic[serverId.toLong() and 0xFFFF_FFFFL] ?: return
                    if (key == DEFAULT_ACTION_ID) {
                        fire(NotificationEvent.Activated(publicId))
                    } else {
                        fire(NotificationEvent.ActionInvoked(publicId, key))
                    }
                }
                "NotificationClosed" -> {
                    val serverId = bindings.readUint32(call, iter) ?: return
                    bindings.handle("dbus_message_iter_next").invokeExact(iter) as Int
                    val reason = bindings.readUint32(call, iter) ?: 4
                    val key = serverId.toLong() and 0xFFFF_FFFFL
                    val publicId = serverToPublic.remove(key) ?: return
                    publicToServer.remove(publicId)
                    fire(NotificationEvent.Dismissed(publicId, mapReason(reason)))
                }
                else -> Unit  // ActivationToken and friends -- ignore
            }
        }
    }

    private fun fire(event: NotificationEvent) = listeners.fire(event)

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun newCall(call: Arena, member: String): MemorySegment? {
        val msg = bindings.handle("dbus_message_new_method_call").invokeExact(
            call.allocateUtf8(BUS_NAME),
            call.allocateUtf8(OBJECT_PATH),
            call.allocateUtf8(IFACE),
            call.allocateUtf8(member),
        ) as MemorySegment
        return if (msg.address() == 0L) null else msg
    }

    private fun freeErrorIfSet(error: MemorySegment) {
        if ((bindings.handle("dbus_error_is_set").invokeExact(error) as Int) != 0) {
            runCatching { bindings.handle("dbus_error_free").invokeExact(error) as Unit }
        }
    }

    /**
     * Whether the server treats the body as markup. A server that was absent at
     * create time may have started since, so an unknown answer is asked again
     * on every post until one replies. With no reply the text goes unescaped:
     * a server without the capability would show the entities literally.
     */
    private fun serverParsesBodyMarkup(call: Arena): Boolean {
        bodyMarkup?.let { return it }
        val advertised = queryCapabilities(bindings, connection, call, CAPABILITIES_TIMEOUT_MS) ?: return false
        return (CAP_BODY_MARKUP in advertised).also { bodyMarkup = it }
    }

    private fun nextAnonId(): String = "ln-${anonCounter++}"

    /**
     * Decode encoded image bytes (PNG, etc.) into tightly packed,
     * non-premultiplied RGBA for the `image-data` hint. Returns null on a
     * decode failure or null input -- the notification still posts, just
     * without the inline image.
     */
    private fun decodeRgba(bytes: ByteArray?): RgbaImage? {
        if (bytes == null) return null
        return runCatching {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            val w = img.width
            val h = img.height
            val argb = IntArray(w * h)
            img.getRGB(0, 0, w, h, argb, 0, w)
            val out = ByteArray(w * h * 4)
            for (i in argb.indices) {
                val px = argb[i]
                out[i * 4] = ((px ushr 16) and 0xFF).toByte()      // R
                out[i * 4 + 1] = ((px ushr 8) and 0xFF).toByte()   // G
                out[i * 4 + 2] = (px and 0xFF).toByte()            // B
                out[i * 4 + 3] = ((px ushr 24) and 0xFF).toByte()  // A
            }
            RgbaImage(w, h, out)
        }.getOrElse {
            log.warn("icon decode failed, posting without image: {}", it.message)
            null
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libnotify.Freedesktop")

        private const val BUS_NAME = "org.freedesktop.Notifications"
        private const val OBJECT_PATH = "/org/freedesktop/Notifications"
        private const val IFACE = "org.freedesktop.Notifications"
        private const val CAP_BODY_MARKUP = "body-markup"

        /** Shorter than a post's reply timeout, so asking first does not double the wait on a hung server. */
        private const val CAPABILITIES_TIMEOUT_MS = 2_000

        /** Reply timeout for the blocking Notify / CloseNotification calls. */
        private const val REPLY_TIMEOUT_MS = 5_000

        /**
         * How long the dispatch thread waits before pumping the bus again.
         *
         * It does not bound how quickly a call this library makes goes out: a
         * task offered to the queue wakes the wait immediately. What it bounds
         * is how long an incoming signal, an action a user clicked or a
         * notification the daemon closed, can sit in the socket before it is
         * read, which is the same hundred milliseconds the sibling tray library
         * has always used.
         */
        private const val SIGNAL_POLL_INTERVAL_MS = 100L

        /**
         * How long a call may wait for the dispatch thread to pick it up. Past
         * this it is dropped unsent, so a caller behind a hung call fails fast
         * and truthfully instead of posting after it has been told null.
         */
        private const val QUEUE_WAIT_MS = 1_000L

        /** Safety net over the native timeouts a started call is already bounded by. */
        private const val WORK_CAP_MS = CAPABILITIES_TIMEOUT_MS + REPLY_TIMEOUT_MS + 1_000L

        /**
         * How long close() waits for the dispatch thread.
         *
         * Deliberately shorter than [REPLY_TIMEOUT_MS]: a host shutting down
         * should not hang five seconds on a wedged daemon. The thread finishes
         * and frees the connection on its own after close() has returned.
         */
        private const val JOIN_TIMEOUT_MS = 2_000L

        fun create(config: NotifierConfig): Notifier? {
            val bindings = DBusBindings.load() ?: run {
                log.info("libdbus not loadable -- freedesktop notifications unavailable")
                return null
            }
            val conn = connectSession(bindings) ?: run {
                log.info("no session bus, notifications unavailable")
                // Nothing was constructed, so nothing will ever call close(): the
                // arena holding the library lookup and every downcall handle has
                // no other owner. Release it here or a machine with no session
                // bus leaks it for the process lifetime.
                runCatching { bindings.arena.close() }
                return null
            }
            // From here to the constructor, a throw would strand both the
            // connection and the arena: Notifier.create's runCatching turns it
            // into a plain "backend unavailable" and nothing else holds a
            // reference to either.
            return try {
                val advertised = Arena.ofConfined().use { setup ->
                    queryCapabilities(bindings, conn, setup, CAPABILITIES_TIMEOUT_MS)
                }
                FreedesktopNotifier(
                    bindings,
                    conn,
                    config,
                    parseCapabilities(advertised),
                    advertised?.let { CAP_BODY_MARKUP in it },
                )
            } catch (t: Throwable) {
                log.info("notifier setup failed after connecting: {}", t.message)
                closeConnection(bindings, conn)
                runCatching { bindings.arena.close() }
                null
            }
        }

        /**
         * A new private session-bus connection, ready for this backend, or null
         * when there is no bus to reach. Used at create time and to replace a
         * connection whose bus went away.
         */
        private fun connectSession(bindings: DBusBindings): MemorySegment? = Arena.ofConfined().use { arena ->
            val error = arena.allocate(bindings.errorLayout)
            bindings.handle("dbus_error_init").invokeExact(error) as Unit
            // Private, not shared: see the dbus_bus_get_private note in
            // DBusBindings.LOAD_SET. A shared connection lets another libdbus
            // user in the process pop our incoming messages off the single
            // shared queue (and vice versa).
            val conn = bindings.handle("dbus_bus_get_private")
                .invokeExact(DBusBindings.DBUS_BUS_SESSION, error) as MemorySegment
            if (conn.address() == 0L) {
                // libdbus heap-allocates the error strings, the arena won't free them.
                freeErrorIfSet(bindings, error)
                return@use null
            }
            try {
                // Don't let a dropped session bus _exit() the host application.
                bindings.handle("dbus_connection_set_exit_on_disconnect").invokeExact(conn, 0) as Unit
                // Subscribe to ActionInvoked + NotificationClosed. Not fatal if it
                // fails: fire-and-forget notifications still work.
                val rule = arena.allocateUtf8("type='signal',interface='$IFACE'")
                bindings.handle("dbus_bus_add_match").invokeExact(conn, rule, error) as Unit
                if ((bindings.handle("dbus_error_is_set").invokeExact(error) as Int) != 0) {
                    log.warn("AddMatch for notification signals failed, events will not arrive")
                    bindings.handle("dbus_error_free").invokeExact(error) as Unit
                }
                conn
            } catch (t: Throwable) {
                log.info("session bus connection setup failed: {}", t.message)
                closeConnection(bindings, conn)
                null
            }
        }

        /**
         * Private connection: close (detach from the bus, release the socket)
         * before the final unref. A shared dbus_bus_get connection must never be
         * closed, but this backend owns a private one.
         */
        private fun closeConnection(bindings: DBusBindings, conn: MemorySegment) {
            runCatching { bindings.handle("dbus_connection_close").invokeExact(conn) as Unit }
                .onFailure { log.warn("dbus_connection_close threw: {}", it.message) }
            runCatching { bindings.handle("dbus_connection_unref").invokeExact(conn) as Unit }
                .onFailure { log.warn("dbus_connection_unref threw: {}", it.message) }
        }

        /**
         * Synchronous `GetCapabilities` on whichever thread owns [conn] right now:
         * the caller during setup, the dispatch thread afterwards. Returns the
         * server's capability strings, or null when the call fails, typically
         * because no daemon is running yet.
         */
        private fun queryCapabilities(
            bindings: DBusBindings,
            conn: MemorySegment,
            arena: Arena,
            timeoutMs: Int,
        ): List<String>? {
            val msg = bindings.handle("dbus_message_new_method_call").invokeExact(
                arena.allocateUtf8(BUS_NAME),
                arena.allocateUtf8(OBJECT_PATH),
                arena.allocateUtf8(IFACE),
                arena.allocateUtf8("GetCapabilities"),
            ) as MemorySegment
            if (msg.address() == 0L) return null
            try {
                val error = arena.allocate(bindings.errorLayout)
                bindings.handle("dbus_error_init").invokeExact(error) as Unit
                val reply = bindings.handle("dbus_connection_send_with_reply_and_block")
                    .invokeExact(conn, msg, timeoutMs, error) as MemorySegment
                if (reply.address() == 0L) {
                    if ((bindings.handle("dbus_error_is_set").invokeExact(error) as Int) != 0) {
                        runCatching { bindings.handle("dbus_error_free").invokeExact(error) as Unit }
                    }
                    return null
                }
                try {
                    val caps = mutableListOf<String>()
                    Arena.ofConfined().use { call ->
                        val iter = call.allocate(bindings.messageIterLayout)
                        if ((bindings.handle("dbus_message_iter_init").invokeExact(reply, iter) as Int) == 0) return null
                        if ((bindings.handle("dbus_message_iter_get_arg_type").invokeExact(iter) as Int).toByte()
                            != DBusBindings.DBUS_TYPE_ARRAY
                        ) {
                            return null
                        }
                        val sub = call.allocate(bindings.messageIterLayout)
                        bindings.handle("dbus_message_iter_recurse").invokeExact(iter, sub) as Unit
                        while (true) {
                            val et = bindings.handle("dbus_message_iter_get_arg_type").invokeExact(sub) as Int
                            if (et.toByte() == DBusBindings.DBUS_TYPE_INVALID) break
                            bindings.readString(call, sub)?.let { caps.add(it) }
                            bindings.handle("dbus_message_iter_next").invokeExact(sub) as Int
                        }
                    }
                    return caps
                } finally {
                    runCatching { bindings.handle("dbus_message_unref").invokeExact(reply) as Unit }
                }
            } finally {
                runCatching { bindings.handle("dbus_message_unref").invokeExact(msg) as Unit }
            }
        }

        private fun freeErrorIfSet(bindings: DBusBindings, error: MemorySegment) {
            if ((bindings.handle("dbus_error_is_set").invokeExact(error) as Int) != 0) {
                runCatching { bindings.handle("dbus_error_free").invokeExact(error) as Unit }
            }
        }

        /**
         * Map the freedesktop `GetCapabilities` string set onto
         * [NotifierCapabilities]. A null list (server absent at setup) takes the
         * spec baseline -- every freedesktop server supports replace via
         * `replaces_id` and emits `NotificationClosed`, so those stay true; the
         * rest default optimistically and a daemon that lacks them simply drops
         * the facet at post time.
         */
        internal fun parseCapabilities(caps: List<String>?): NotifierCapabilities {
            if (caps == null) {
                return NotifierCapabilities(
                    actions = true,
                    maxActions = Int.MAX_VALUE,
                    bodyMarkup = false,
                    icons = true,
                    replace = true,
                    closeEvents = true,
                    urgency = true,
                )
            }
            val actions = "actions" in caps
            return NotifierCapabilities(
                actions = actions,
                maxActions = if (actions) Int.MAX_VALUE else 0,
                bodyMarkup = CAP_BODY_MARKUP in caps,
                icons = "icon-static" in caps || "icon-multi" in caps || "body-images" in caps,
                replace = true,
                closeEvents = true,
                urgency = true,
            )
        }

        /** Encode plain text for a server that parses the body as XML-based markup. */
        internal fun escapeBodyMarkup(text: String): String {
            val sb = StringBuilder(text.length + 8)
            for (ch in text) {
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        /** freedesktop urgency hint byte: low=0, normal=1, critical=2. */
        internal fun urgencyByte(urgency: Urgency): Byte = when (urgency) {
            Urgency.LOW -> 0
            Urgency.NORMAL -> 1
            Urgency.CRITICAL -> 2
        }

        /** freedesktop expire_timeout: -1 server default, 0 never, else exact ms. */
        internal fun expireMillis(timeout: Timeout): Int = when (timeout) {
            is Timeout.ServerDefault -> -1
            is Timeout.Never -> 0
            is Timeout.After -> timeout.millis
        }

        /** freedesktop NotificationClosed reason codes (spec section "Signals"). */
        internal fun mapReason(code: Int): DismissReason = when (code) {
            1 -> DismissReason.EXPIRED
            2 -> DismissReason.DISMISSED_BY_USER
            3 -> DismissReason.CLOSED
            else -> DismissReason.UNKNOWN
        }
    }
}
