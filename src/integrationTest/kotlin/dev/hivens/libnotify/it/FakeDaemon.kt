package dev.hivens.libnotify.it

import io.kotest.assertions.fail
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One `Notify` call as the server saw it on the wire. */
internal class ReceivedNotify(
    val id: Int,
    val appName: String,
    val replacesId: Int,
    val appIcon: String,
    val summary: String,
    val body: String,
    val actions: List<String>,
    val hints: Map<String, Any?>,
    val expireTimeout: Int,
) {
    /** The `image-data` hint: width, height, rowstride, has_alpha, bits, channels, pixels. */
    val image: ReceivedImage?
        get() {
            val fields = hints["image-data"] as List<*>? ?: return null
            return ReceivedImage(
                width = fields[0] as Int,
                height = fields[1] as Int,
                rowstride = fields[2] as Int,
                hasAlpha = fields[3] as Boolean,
                bitsPerSample = fields[4] as Int,
                channels = fields[5] as Int,
                pixels = fields[6] as ByteArray,
            )
        }
}

internal class ReceivedImage(
    val width: Int,
    val height: Int,
    val rowstride: Int,
    val hasAlpha: Boolean,
    val bitsPerSample: Int,
    val channels: Int,
    val pixels: ByteArray,
)

/** How the fake answers method calls. */
internal sealed interface ServerMode {
    /** Answers at once. */
    data object Normal : ServerMode

    /** Stalls before handling each call, and everything behind it waits too. */
    data class Slow(val millis: Long) : ServerMode

    /** Reads the call and never answers. */
    data object Silent : ServerMode

    /** Answers with a D-Bus error. */
    data object Failing : ServerMode
}

/**
 * A notification server on the test bus, built on libdbus but sharing nothing
 * with the code under test. Stopping one and starting another models a daemon
 * restart: the name changes owner and ids start over.
 *
 * It is as strict as a GDBus server: a call on the wrong object path or
 * interface, or with a signature the spec does not define, gets an error reply
 * and is recorded as a protocol violation. [close] fails the test for any
 * violation and for a server thread that died, so neither hides behind a
 * timeout somewhere else.
 */
internal class FakeDaemon(
    private val capabilities: List<String> = FULL_CAPABILITIES,
    firstId: Int = 1,
) : AutoCloseable {

    @Volatile var mode: ServerMode = ServerMode.Normal

    private val notifies = LinkedBlockingQueue<ReceivedNotify>()
    private val closeRequests = LinkedBlockingQueue<Int>()
    private val commands = LinkedBlockingQueue<(MemorySegment) -> Unit>()
    private val violations = ConcurrentLinkedQueue<String>()
    private val live = HashSet<Int>()
    private var nextId = firstId

    @Volatile private var died: Throwable? = null

    private val conn = connect(TestBus.address).also { ownNotificationsName(it) }

    @Volatile private var running = true
    private val thread = Thread({ serve() }, "fake-notification-daemon").apply {
        isDaemon = true
        start()
    }

    /** The next `Notify` the server handled, in order. */
    fun awaitNotify(timeoutMs: Long = 5_000): ReceivedNotify {
        val got = notifies.poll(timeoutMs, TimeUnit.MILLISECONDS)
        failIfBroken()
        return got ?: fail("the server got no Notify within $timeoutMs ms")
    }

    /**
     * Fails if a `Notify` arrives within [windowMs]. Size the window past the time
     * a stray call would take to be handled, including any [ServerMode.Slow] stall.
     */
    fun assertNoNotify(windowMs: Long = 300) {
        notifies.poll(windowMs, TimeUnit.MILLISECONDS)?.let { fail("the server got an unexpected Notify: '${it.summary}'") }
        failIfBroken()
    }

    fun awaitCloseRequest(timeoutMs: Long = 5_000): Int {
        val got = closeRequests.poll(timeoutMs, TimeUnit.MILLISECONDS)
        failIfBroken()
        return got ?: fail("the server got no CloseNotification within $timeoutMs ms")
    }

    fun assertNoCloseRequest(windowMs: Long = 300) {
        closeRequests.poll(windowMs, TimeUnit.MILLISECONDS)?.let { fail("the server got an unexpected CloseNotification for $it") }
        failIfBroken()
    }

    /** The user clicked: the reserved `default` key for the body, an action id for a button. */
    fun click(id: Int, key: String = "default"): Unit = whenIdle { conn ->
        sendSignal(conn, OBJECT_PATH, "ActionInvoked") { iter, arena ->
            appendUint32(iter, arena, id)
            appendString(iter, arena, key)
        }
    }

    fun closed(id: Int, reason: Int): Unit = whenIdle { conn ->
        live.remove(id)
        sendClosed(conn, id, reason)
    }

    private fun whenIdle(command: (MemorySegment) -> Unit) {
        commands.add(command)
    }

    private fun failIfBroken() {
        died?.let { throw AssertionError("the fake server's thread died", it) }
        if (violations.isNotEmpty()) fail("the library broke the protocol: ${violations.joinToString("; ")}")
    }

    private fun serve() {
        try {
            while (running) {
                while (true) {
                    val command = commands.poll() ?: break
                    command(conn)
                }
                // False once the bus is gone, and then it returns at once: back
                // off instead of spinning until the test closes this server.
                if ((Dbus.readWrite.invokeExact(conn, 5) as Int) == 0) Thread.sleep(20)
                while (running) {
                    val msg = Dbus.popMessage.invokeExact(conn) as MemorySegment
                    if (msg.address() == 0L) break
                    try {
                        handle(msg)
                    } finally {
                        Dbus.messageUnref.invokeExact(msg) as Unit
                    }
                }
            }
        } catch (t: Throwable) {
            died = t
        }
    }

    private fun handle(msg: MemorySegment) {
        if ((Dbus.messageGetType.invokeExact(msg) as Int) != METHOD_CALL) return
        val member = messageString(Dbus.messageGetMember, msg)
        // org.freedesktop.DBus.Peer and the like go to every name, they are not ours to judge.
        val iface = messageString(Dbus.messageGetInterface, msg)
        if (iface != null && iface != IFACE) return answerError(msg, UNKNOWN_INTERFACE, "no interface $iface here")
        val path = messageString(Dbus.messageGetPath, msg)
        val signature = messageString(Dbus.messageGetSignature, msg)
        val expected = SIGNATURES[member]
        when {
            path != OBJECT_PATH -> return violation(msg, UNKNOWN_OBJECT, "$member called on '$path'")
            iface == null -> return violation(msg, UNKNOWN_METHOD, "$member called without an interface")
            expected == null -> return violation(msg, UNKNOWN_METHOD, "unknown method '$member'")
            signature != expected -> return violation(msg, INVALID_ARGS, "$member sent '$signature', the spec says '$expected'")
        }
        val current = mode
        if (current is ServerMode.Slow) Thread.sleep(current.millis)
        when (member) {
            "GetCapabilities" -> answer(msg, current) { iter, arena -> appendStringArray(iter, arena, capabilities) }
            "Notify" -> {
                val id = record(msg, current)
                answer(msg, current) { iter, arena -> appendUint32(iter, arena, id) }
            }
            "CloseNotification" -> {
                val id = readArgs(msg)[0] as Int
                closeRequests.add(id)
                answer(msg, current) { _, _ -> }
                if (current !is ServerMode.Silent && current !is ServerMode.Failing && live.remove(id)) {
                    sendClosed(conn, id, CLOSED_BY_CALL)
                }
            }
        }
    }

    private fun violation(msg: MemorySegment, errorName: String, what: String) {
        violations.add(what)
        answerError(msg, errorName, what)
    }

    private fun record(msg: MemorySegment, current: ServerMode): Int {
        val args = readArgs(msg)
        val replacesId = args[1] as Int
        val id = if (replacesId in live) replacesId else nextId++
        if (current !is ServerMode.Failing) live.add(id)
        @Suppress("UNCHECKED_CAST")
        notifies.add(
            ReceivedNotify(
                id = id,
                appName = args[0] as String,
                replacesId = replacesId,
                appIcon = args[2] as String,
                summary = args[3] as String,
                body = args[4] as String,
                actions = args[5] as List<String>,
                hints = (args[6] as List<Pair<String, Any?>>).toMap(),
                expireTimeout = args[7] as Int,
            ),
        )
        return id
    }

    private fun answer(msg: MemorySegment, current: ServerMode, fill: (MemorySegment, Arena) -> Unit) {
        when (current) {
            ServerMode.Silent -> return
            ServerMode.Failing -> {
                answerError(msg, FAILED, "the fake server was told to fail")
                return
            }
            else -> Unit
        }
        Arena.ofConfined().use { arena ->
            val reply = Dbus.newMethodReturn.invokeExact(msg) as MemorySegment
            val iter = arena.newIter()
            Dbus.iterInitAppend.invokeExact(reply, iter) as Unit
            fill(iter, arena)
            Dbus.send.invokeExact(conn, reply, MemorySegment.NULL) as Int
            Dbus.messageUnref.invokeExact(reply) as Unit
            Dbus.flush.invokeExact(conn) as Unit
        }
    }

    private fun answerError(msg: MemorySegment, name: String, text: String): Unit = Arena.ofConfined().use { arena ->
        val reply = Dbus.newError.invokeExact(msg, arena.allocateFrom(name), arena.allocateFrom(text)) as MemorySegment
        Dbus.send.invokeExact(conn, reply, MemorySegment.NULL) as Int
        Dbus.messageUnref.invokeExact(reply) as Unit
        Dbus.flush.invokeExact(conn) as Unit
    }

    override fun close() {
        running = false
        thread.join(10_000)
        // A thread still inside libdbus owns the connection, freeing it under
        // that call would crash the test JVM instead of failing the test.
        check(!thread.isAlive) { "the fake server's thread did not stop" }
        disconnect(conn)
        failIfBroken()
    }

    companion object {
        private const val METHOD_CALL = 1
        private const val CLOSED_BY_CALL = 3

        private const val FAILED = "org.freedesktop.DBus.Error.Failed"
        private const val UNKNOWN_METHOD = "org.freedesktop.DBus.Error.UnknownMethod"
        private const val UNKNOWN_OBJECT = "org.freedesktop.DBus.Error.UnknownObject"
        private const val UNKNOWN_INTERFACE = "org.freedesktop.DBus.Error.UnknownInterface"
        private const val INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs"

        /** Argument signatures from the Desktop Notifications Specification. */
        private val SIGNATURES = mapOf(
            "GetCapabilities" to "",
            "Notify" to "susssasa{sv}i",
            "CloseNotification" to "u",
        )

        val FULL_CAPABILITIES = listOf("actions", "body", "body-markup", "icon-static")

        private fun sendClosed(conn: MemorySegment, id: Int, reason: Int) {
            sendSignal(conn, OBJECT_PATH, "NotificationClosed") { iter, arena ->
                appendUint32(iter, arena, id)
                appendUint32(iter, arena, reason)
            }
        }
    }
}

/** Another client on the same bus, with no claim to the notification service. */
internal class Stranger : AutoCloseable {
    private val conn = connect(TestBus.address)

    /**
     * Sends from the real service's object path and interface, so the only thing
     * that gives it away is the sender.
     */
    fun emitActionInvoked(id: Int, key: String) {
        sendSignal(conn, OBJECT_PATH, "ActionInvoked") { iter, arena ->
            appendUint32(iter, arena, id)
            appendString(iter, arena, key)
        }
    }

    override fun close() = disconnect(conn)
}
