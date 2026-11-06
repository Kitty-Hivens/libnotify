package dev.hivens.libnotify.it

import io.kotest.assertions.fail
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
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
 */
internal class FakeDaemon(
    private val capabilities: List<String> = FULL_CAPABILITIES,
    firstId: Int = 1,
) : AutoCloseable {

    @Volatile var mode: ServerMode = ServerMode.Normal

    private val notifies = LinkedBlockingQueue<ReceivedNotify>()
    private val closeRequests = LinkedBlockingQueue<Int>()
    private val commands = LinkedBlockingQueue<(MemorySegment) -> Unit>()
    private val live = HashSet<Int>()
    private var nextId = firstId

    private val conn = connect(TestBus.address).also { ownNotificationsName(it) }

    @Volatile private var running = true
    private val thread = Thread({ serve() }, "fake-notification-daemon").apply {
        isDaemon = true
        start()
    }

    /** The next `Notify` the server handled, in order. */
    fun awaitNotify(timeoutMs: Long = 5_000): ReceivedNotify =
        notifies.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: fail("the server got no Notify within $timeoutMs ms")

    /** Fails if a `Notify` arrives within [windowMs]. */
    fun assertNoNotify(windowMs: Long = 300) {
        notifies.poll(windowMs, TimeUnit.MILLISECONDS)?.let { fail("the server got an unexpected Notify: '${it.summary}'") }
    }

    fun awaitCloseRequest(timeoutMs: Long = 5_000): Int =
        closeRequests.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: fail("the server got no CloseNotification within $timeoutMs ms")

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

    private fun serve() {
        while (running) {
            while (true) {
                val command = commands.poll() ?: break
                command(conn)
            }
            Dbus.readWrite.invokeExact(conn, 5) as Int
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
    }

    private fun handle(msg: MemorySegment) {
        if ((Dbus.messageGetType.invokeExact(msg) as Int) != METHOD_CALL) return
        val memberPointer = Dbus.messageGetMember.invokeExact(msg) as MemorySegment
        val member = memberPointer.reinterpret(Long.MAX_VALUE).getString(0)
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
            else -> answerError(msg)
        }
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
                answerError(msg)
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

    private fun answerError(msg: MemorySegment): Unit = Arena.ofConfined().use { arena ->
        val reply = Dbus.newError.invokeExact(
            msg,
            arena.allocateFrom("org.freedesktop.DBus.Error.Failed"),
            arena.allocateFrom("the fake server was told to fail"),
        ) as MemorySegment
        Dbus.send.invokeExact(conn, reply, MemorySegment.NULL) as Int
        Dbus.messageUnref.invokeExact(reply) as Unit
        Dbus.flush.invokeExact(conn) as Unit
    }

    override fun close() {
        running = false
        thread.join(10_000)
        disconnect(conn)
    }

    companion object {
        private const val METHOD_CALL = 1
        private const val CLOSED_BY_CALL = 3

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

    fun emitActionInvoked(id: Int, key: String, path: String = "/not/the/notification/service") {
        sendSignal(conn, path, "ActionInvoked") { iter, arena ->
            appendUint32(iter, arena, id)
            appendString(iter, arena, key)
        }
    }

    override fun close() = disconnect(conn)
}
