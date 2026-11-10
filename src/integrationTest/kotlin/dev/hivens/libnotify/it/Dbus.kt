package dev.hivens.libnotify.it

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

internal const val BUS_NAME = "org.freedesktop.Notifications"
internal const val OBJECT_PATH = "/org/freedesktop/Notifications"
internal const val IFACE = "org.freedesktop.Notifications"

private const val MESSAGE_ITER_SIZE = 80L
private const val ERROR_SIZE = 32L
private const val REQUEST_NAME_DO_NOT_QUEUE = 4
private const val REQUEST_NAME_PRIMARY_OWNER = 1

/**
 * The server side of libdbus that the fake daemon needs. Separate from the
 * library's own bindings on purpose: the tests must not trust the code under test
 * to describe the wire format.
 */
internal object Dbus {
    private val pointer = ValueLayout.ADDRESS
    private val int = ValueLayout.JAVA_INT
    private val linker = Linker.nativeLinker()
    private val lib = SymbolLookup.libraryLookup("libdbus-1.so.3", Arena.global())

    private fun fn(name: String, ret: MemoryLayout?, vararg args: MemoryLayout): MethodHandle {
        val symbol = lib.find(name).orElseThrow()
        val descriptor = if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args)
        return linker.downcallHandle(symbol, descriptor)
    }

    val errorInit = fn("dbus_error_init", null, pointer)
    val errorIsSet = fn("dbus_error_is_set", int, pointer)
    val errorFree = fn("dbus_error_free", null, pointer)

    val openPrivate = fn("dbus_connection_open_private", pointer, pointer, pointer)
    val busRegister = fn("dbus_bus_register", int, pointer, pointer)
    val requestName = fn("dbus_bus_request_name", int, pointer, pointer, int, pointer)
    val setExitOnDisconnect = fn("dbus_connection_set_exit_on_disconnect", null, pointer, int)
    val connectionClose = fn("dbus_connection_close", null, pointer)
    val connectionUnref = fn("dbus_connection_unref", null, pointer)
    val readWrite = fn("dbus_connection_read_write", int, pointer, int)
    val popMessage = fn("dbus_connection_pop_message", pointer, pointer)
    val send = fn("dbus_connection_send", int, pointer, pointer, pointer)
    val flush = fn("dbus_connection_flush", null, pointer)

    val messageUnref = fn("dbus_message_unref", null, pointer)
    val messageGetType = fn("dbus_message_get_type", int, pointer)
    val messageGetMember = fn("dbus_message_get_member", pointer, pointer)
    val messageGetPath = fn("dbus_message_get_path", pointer, pointer)
    val messageGetInterface = fn("dbus_message_get_interface", pointer, pointer)
    val messageGetSignature = fn("dbus_message_get_signature", pointer, pointer)
    val newMethodReturn = fn("dbus_message_new_method_return", pointer, pointer)
    val newError = fn("dbus_message_new_error", pointer, pointer, pointer, pointer)
    val newSignal = fn("dbus_message_new_signal", pointer, pointer, pointer, pointer)
    val newMethodCall = fn("dbus_message_new_method_call", pointer, pointer, pointer, pointer, pointer)
    val sendWithReplyAndBlock = fn("dbus_connection_send_with_reply_and_block", pointer, pointer, pointer, int, pointer)

    val iterInit = fn("dbus_message_iter_init", int, pointer, pointer)
    val iterInitAppend = fn("dbus_message_iter_init_append", null, pointer, pointer)
    val iterNext = fn("dbus_message_iter_next", int, pointer)
    val iterRecurse = fn("dbus_message_iter_recurse", null, pointer, pointer)
    val iterArgType = fn("dbus_message_iter_get_arg_type", int, pointer)
    val iterElementType = fn("dbus_message_iter_get_element_type", int, pointer)
    val iterGetBasic = fn("dbus_message_iter_get_basic", null, pointer, pointer)
    val iterGetFixedArray = fn("dbus_message_iter_get_fixed_array", null, pointer, pointer, pointer)
    val iterAppendBasic = fn("dbus_message_iter_append_basic", int, pointer, int, pointer)
    val iterOpen = fn("dbus_message_iter_open_container", int, pointer, int, pointer, pointer)
    val iterClose = fn("dbus_message_iter_close_container", int, pointer, pointer)
}

internal fun Arena.newIter(): MemorySegment = allocate(MESSAGE_ITER_SIZE, 8)

private fun Arena.newError(): MemorySegment {
    val error = allocate(ERROR_SIZE, 8)
    Dbus.errorInit.invokeExact(error) as Unit
    return error
}

private fun failOnError(error: MemorySegment, what: String) {
    if ((Dbus.errorIsSet.invokeExact(error) as Int) == 0) return
    val text = error.get(ValueLayout.ADDRESS, 8).reinterpret(Long.MAX_VALUE).getString(0)
    Dbus.errorFree.invokeExact(error) as Unit
    error("$what: $text")
}

/** A connection to the test bus by explicit address, so it cannot land on any other bus. */
internal fun connect(address: String): MemorySegment = Arena.ofConfined().use { arena ->
    val error = arena.newError()
    val conn = Dbus.openPrivate.invokeExact(arena.allocateFrom(address), error) as MemorySegment
    failOnError(error, "connect to $address")
    Dbus.setExitOnDisconnect.invokeExact(conn, 0) as Unit
    Dbus.busRegister.invokeExact(conn, error) as Int
    failOnError(error, "register on $address")
    conn
}

/** A `const char *` accessor on a message, or null. */
internal fun messageString(accessor: MethodHandle, msg: MemorySegment): String? {
    val pointer = accessor.invokeExact(msg) as MemorySegment
    return if (pointer.address() == 0L) null else pointer.reinterpret(Long.MAX_VALUE).getString(0)
}

internal fun disconnect(conn: MemorySegment) {
    Dbus.connectionClose.invokeExact(conn) as Unit
    Dbus.connectionUnref.invokeExact(conn) as Unit
}

internal fun ownNotificationsName(conn: MemorySegment): Unit = Arena.ofConfined().use { arena ->
    val error = arena.newError()
    val reply = Dbus.requestName.invokeExact(
        conn,
        arena.allocateFrom(BUS_NAME),
        REQUEST_NAME_DO_NOT_QUEUE,
        error,
    ) as Int
    failOnError(error, "request $BUS_NAME")
    check(reply == REQUEST_NAME_PRIMARY_OWNER) { "$BUS_NAME is already owned on the test bus" }
}

/** Every argument of [msg] as Kotlin values: arrays and structs as lists, dict entries as pairs. */
internal fun readArgs(msg: MemorySegment): List<Any?> = Arena.ofConfined().use { arena ->
    val iter = arena.newIter()
    if ((Dbus.iterInit.invokeExact(msg, iter) as Int) == 0) return@use emptyList()
    readSiblings(iter, arena)
}

private fun readSiblings(iter: MemorySegment, arena: Arena): List<Any?> {
    val values = ArrayList<Any?>()
    while ((Dbus.iterArgType.invokeExact(iter) as Int) != 0) {
        values.add(readValue(iter, arena))
        Dbus.iterNext.invokeExact(iter) as Int
    }
    return values
}

private fun readValue(iter: MemorySegment, arena: Arena): Any? {
    val type = Char(Dbus.iterArgType.invokeExact(iter) as Int)
    return when (type) {
        's', 'o', 'g' -> {
            val out = arena.allocate(ValueLayout.ADDRESS)
            Dbus.iterGetBasic.invokeExact(iter, out) as Unit
            out.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0)
        }
        'i', 'u' -> {
            val out = arena.allocate(ValueLayout.JAVA_INT)
            Dbus.iterGetBasic.invokeExact(iter, out) as Unit
            out.get(ValueLayout.JAVA_INT, 0)
        }
        'b' -> {
            val out = arena.allocate(ValueLayout.JAVA_INT)
            Dbus.iterGetBasic.invokeExact(iter, out) as Unit
            out.get(ValueLayout.JAVA_INT, 0) != 0
        }
        'y' -> {
            val out = arena.allocate(ValueLayout.JAVA_BYTE)
            Dbus.iterGetBasic.invokeExact(iter, out) as Unit
            out.get(ValueLayout.JAVA_BYTE, 0)
        }
        'n', 'q' -> {
            val out = arena.allocate(ValueLayout.JAVA_SHORT)
            Dbus.iterGetBasic.invokeExact(iter, out) as Unit
            out.get(ValueLayout.JAVA_SHORT, 0)
        }
        'x', 't' -> {
            val out = arena.allocate(ValueLayout.JAVA_LONG)
            Dbus.iterGetBasic.invokeExact(iter, out) as Unit
            out.get(ValueLayout.JAVA_LONG, 0)
        }
        'd' -> {
            val out = arena.allocate(ValueLayout.JAVA_DOUBLE)
            Dbus.iterGetBasic.invokeExact(iter, out) as Unit
            out.get(ValueLayout.JAVA_DOUBLE, 0)
        }
        'v' -> {
            val sub = arena.newIter()
            Dbus.iterRecurse.invokeExact(iter, sub) as Unit
            readValue(sub, arena)
        }
        'a' -> {
            val elementType = Char(Dbus.iterElementType.invokeExact(iter) as Int)
            val sub = arena.newIter()
            Dbus.iterRecurse.invokeExact(iter, sub) as Unit
            if (elementType == 'y') readBytes(sub, arena) else readSiblings(sub, arena)
        }
        'r' -> {
            val sub = arena.newIter()
            Dbus.iterRecurse.invokeExact(iter, sub) as Unit
            readSiblings(sub, arena)
        }
        'e' -> {
            val sub = arena.newIter()
            Dbus.iterRecurse.invokeExact(iter, sub) as Unit
            val entry = readSiblings(sub, arena)
            entry[0] to entry[1]
        }
        else -> error("unexpected D-Bus type '$type'")
    }
}

private fun readBytes(sub: MemorySegment, arena: Arena): ByteArray {
    val data = arena.allocate(ValueLayout.ADDRESS)
    val count = arena.allocate(ValueLayout.JAVA_INT)
    Dbus.iterGetFixedArray.invokeExact(sub, data, count) as Unit
    val size = count.get(ValueLayout.JAVA_INT, 0)
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    MemorySegment.copy(
        data.get(ValueLayout.ADDRESS, 0).reinterpret(size.toLong()),
        ValueLayout.JAVA_BYTE,
        0,
        bytes,
        0,
        size,
    )
    return bytes
}

internal fun appendString(iter: MemorySegment, arena: Arena, value: String) {
    val slot = arena.allocate(ValueLayout.ADDRESS)
    slot.set(ValueLayout.ADDRESS, 0, arena.allocateFrom(value))
    Dbus.iterAppendBasic.invokeExact(iter, 's'.code, slot) as Int
}

internal fun appendUint32(iter: MemorySegment, arena: Arena, value: Int) {
    val slot = arena.allocate(ValueLayout.JAVA_INT)
    slot.set(ValueLayout.JAVA_INT, 0, value)
    Dbus.iterAppendBasic.invokeExact(iter, 'u'.code, slot) as Int
}

internal fun appendStringArray(iter: MemorySegment, arena: Arena, values: List<String>) {
    val sub = arena.newIter()
    Dbus.iterOpen.invokeExact(iter, 'a'.code, arena.allocateFrom("s"), sub) as Int
    for (value in values) appendString(sub, arena, value)
    Dbus.iterClose.invokeExact(iter, sub) as Int
}

/**
 * Call [member] on the notification service from [conn], with whatever arguments
 * [fill] appends, and say whether it answered with a method return. For testing
 * the fake server itself: the library's own calls go through its own bindings.
 */
internal fun callService(
    conn: MemorySegment,
    path: String,
    member: String,
    fill: (MemorySegment, Arena) -> Unit,
): Boolean = Arena.ofConfined().use { arena ->
    val msg = Dbus.newMethodCall.invokeExact(
        arena.allocateFrom(BUS_NAME),
        arena.allocateFrom(path),
        arena.allocateFrom(IFACE),
        arena.allocateFrom(member),
    ) as MemorySegment
    val iter = arena.newIter()
    Dbus.iterInitAppend.invokeExact(msg, iter) as Unit
    fill(iter, arena)
    val error = arena.newError()
    val reply = Dbus.sendWithReplyAndBlock.invokeExact(conn, msg, 5_000, error) as MemorySegment
    Dbus.messageUnref.invokeExact(msg) as Unit
    if (reply.address() == 0L) {
        Dbus.errorFree.invokeExact(error) as Unit
        return@use false
    }
    Dbus.messageUnref.invokeExact(reply) as Unit
    true
}

internal fun sendSignal(
    conn: MemorySegment,
    path: String,
    member: String,
    fill: (MemorySegment, Arena) -> Unit,
): Unit = Arena.ofConfined().use { arena ->
    val msg = Dbus.newSignal.invokeExact(
        arena.allocateFrom(path),
        arena.allocateFrom(IFACE),
        arena.allocateFrom(member),
    ) as MemorySegment
    val iter = arena.newIter()
    Dbus.iterInitAppend.invokeExact(msg, iter) as Unit
    fill(iter, arena)
    Dbus.send.invokeExact(conn, msg, MemorySegment.NULL) as Int
    Dbus.messageUnref.invokeExact(msg) as Unit
    Dbus.flush.invokeExact(conn) as Unit
}
