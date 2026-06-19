package dev.hivens.libnotify.linux

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Panama bindings to `libdbus-1` -- the reference D-Bus client library
 * shipped on every desktop Linux. Loaded once per process via
 * [DBusBindings.load]; the resulting object holds method handles for the
 * client subset that the [FreedesktopNotifier] uses.
 *
 * Unlike a service that owns a bus name, the notifier is a plain *client* of
 * `org.freedesktop.Notifications`: it sends method calls and reads their
 * replies, plus subscribes (`AddMatch`) to the two signals the spec defines.
 * That needs no Panama upcall stubs at all -- incoming signals are pulled off
 * the connection with `dbus_connection_read_write` + `dbus_connection_pop_message`
 * on a thread we own.
 *
 * Reference for the constants and shapes: `dbus/dbus.h` upstream
 * (https://gitlab.freedesktop.org/dbus/dbus). Where this file says
 * "DBUS_TYPE_FOO" the value matches the C macro of the same name.
 */
internal class DBusBindings private constructor(
    val arena: Arena,
    private val handles: Map<String, MethodHandle>,
) {
    /**
     * Look up a previously-resolved handle. Throws if the symbol wasn't in the
     * load-time set -- programmer error, not a runtime fallback.
     */
    fun handle(name: String): MethodHandle =
        handles[name] ?: error("DBus handle not loaded: $name. Add to LOAD_SET in DBusBindings.load.")

    companion object {
        /** D-Bus bus types from dbus/dbus-shared.h. */
        const val DBUS_BUS_SESSION: Int = 0
        const val DBUS_BUS_SYSTEM:  Int = 1

        /** Message types from dbus/dbus-protocol.h. */
        const val DBUS_MESSAGE_TYPE_SIGNAL: Int = 4

        /** Type signatures from dbus/dbus-protocol.h. Single-byte ASCII. */
        const val DBUS_TYPE_INVALID:     Byte = 0
        const val DBUS_TYPE_BYTE:        Byte = 'y'.code.toByte()
        const val DBUS_TYPE_BOOLEAN:     Byte = 'b'.code.toByte()
        const val DBUS_TYPE_INT32:       Byte = 'i'.code.toByte()
        const val DBUS_TYPE_UINT32:      Byte = 'u'.code.toByte()
        const val DBUS_TYPE_STRING:      Byte = 's'.code.toByte()
        const val DBUS_TYPE_OBJECT_PATH: Byte = 'o'.code.toByte()
        const val DBUS_TYPE_ARRAY:       Byte = 'a'.code.toByte()
        const val DBUS_TYPE_VARIANT:     Byte = 'v'.code.toByte()
        const val DBUS_TYPE_STRUCT:      Byte = 'r'.code.toByte()  // also '(' ')'
        const val DBUS_TYPE_DICT_ENTRY:  Byte = 'e'.code.toByte()  // also '{' '}'

        /** Library names -- JDK's libraryLookup tries these in order. */
        private val LIB_CANDIDATES = listOf("dbus-1", "dbus-1.so.3", "libdbus-1.so.3")

        /**
         * Symbols this binding loads. Each entry: name -> (return layout or
         * null for void, arg layouts...). Expand the set when adding new D-Bus
         * features.
         */
        private val LOAD_SET: List<Triple<String, MemoryLayout?, List<MemoryLayout>>> = listOf(
            // Connection lifecycle
            //
            // PRIVATE connection (dbus_bus_get_private), not the process-shared
            // dbus_bus_get one: this backend drains the bus with its own
            // dbus_connection_pop_message loop, and a shared connection's single
            // incoming queue would let another libdbus user in the process (a
            // sibling tray library, say) pop -- and drop -- messages meant for
            // us. A private connection is ours alone.
            Triple("dbus_bus_get_private",
                ValueLayout.ADDRESS,                                  // DBusConnection*
                listOf(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),    // type, error*
            ),
            // A private connection must be closed before the final unref.
            Triple("dbus_connection_close",
                null,                                                  // void
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_connection_unref",
                null,                                                  // void
                listOf(ValueLayout.ADDRESS),
            ),
            // libdbus defaults exit_on_disconnect ON, which _exit()s the whole
            // process if the session bus drops -- turn it off so a notification
            // backend can never take the host application down.
            Triple("dbus_connection_set_exit_on_disconnect",
                null,                                                  // void
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),     // conn, dbus_bool_t
            ),
            Triple("dbus_connection_read_write",
                ValueLayout.JAVA_INT,                                  // dbus_bool_t
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),     // conn, timeout_ms
            ),
            Triple("dbus_connection_pop_message",
                ValueLayout.ADDRESS,                                   // DBusMessage* | NULL
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_connection_send",
                ValueLayout.JAVA_INT,                                  // dbus_bool_t
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), // conn, msg, serial*
            ),
            Triple("dbus_connection_send_with_reply_and_block",
                ValueLayout.ADDRESS,                                   // DBusMessage* reply
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            ),
            Triple("dbus_connection_flush",
                null,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_bus_add_match",
                null,                                                  // void
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), // conn, rule, error*
            ),

            // Message construction / inspection
            Triple("dbus_message_new_method_call",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_unref",
                null,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_type",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_member",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_interface",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS),
            ),

            // Iterator API
            Triple("dbus_message_iter_init",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_init_append",
                null,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_append_basic",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_open_container",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_close_container",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_recurse",
                null,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_next",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_get_arg_type",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_get_basic",
                null,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),

            // Error API
            Triple("dbus_error_init",
                null,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_error_is_set",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_error_free",
                null,
                listOf(ValueLayout.ADDRESS),
            ),
        )

        /**
         * Load libdbus into a fresh shared arena and bind every symbol in
         * [LOAD_SET]. Returns null if the library can't be found OR any
         * required symbol is missing -- in either case the notifier backend
         * degrades to "no notifications", same as a machine with no D-Bus
         * daemon.
         */
        fun load(): DBusBindings? {
            val arena = Arena.ofShared()
            val lookup = LIB_CANDIDATES.firstNotNullOfOrNull { name ->
                runCatching { SymbolLookup.libraryLookup(name, arena) }.getOrNull()
            } ?: run {
                arena.close()
                return null
            }
            val linker = Linker.nativeLinker()
            val handles = HashMap<String, MethodHandle>(LOAD_SET.size * 2)
            for ((name, ret, args) in LOAD_SET) {
                val descriptor = if (ret == null) {
                    FunctionDescriptor.ofVoid(*args.toTypedArray())
                } else {
                    FunctionDescriptor.of(ret, *args.toTypedArray())
                }
                val symbol = lookup.find(name).orElse(null) ?: run {
                    arena.close()
                    return null
                }
                handles[name] = linker.downcallHandle(symbol, descriptor)
            }
            return DBusBindings(arena, handles)
        }
    }

    /**
     * `DBusError` struct layout -- opaque to the caller, but must be allocated
     * with the right size for `dbus_error_init` to populate. 32 bytes on
     * x86_64 / aarch64 (two pointers, a packed-bitfield word + alignment pad,
     * a trailing pointer). We never read the fields; error checking goes
     * through `dbus_error_is_set`.
     */
    val errorLayout: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("name"),
        ValueLayout.ADDRESS.withName("message"),
        ValueLayout.JAVA_INT.withName("flags"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("padding1"),
    )

    /**
     * `DBusMessageIter` is a stack-allocated cursor -- libdbus says it's
     * "small" but exposes no struct definition in the public ABI. The real
     * struct is 72 bytes on x86_64 / aarch64 (two pointers, nine 32-bit
     * dummies + an int pad, then two trailing pointers ending at offset 72),
     * so a 64-byte buffer let libdbus write 8 bytes past the allocation on
     * every `dbus_message_iter_*` call -- silent arena corruption. Reserve 80.
     */
    val messageIterLayout: MemoryLayout = MemoryLayout.sequenceLayout(80, ValueLayout.JAVA_BYTE)
}

/**
 * Allocate a UTF-8 null-terminated string in this arena. libdbus expects
 * `const char *` style strings in every text field.
 */
internal fun Arena.allocateUtf8(s: String): MemorySegment {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val segment = allocate((bytes.size + 1).toLong())
    if (bytes.isNotEmpty()) {
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.size)
    }
    segment.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0)
    return segment
}
