package dev.hivens.libnotify.linux

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Low-level D-Bus argument marshalling on top of [DBusBindings], shared by the
 * notifier instance and its factory. Each function does exactly one append or
 * read against a `DBusMessageIter` and keeps the FFM ceremony (scratch buffers,
 * container open/close pairing, signature strings) out of the call sites.
 *
 * All scratch allocations come from the `call` arena the caller owns -- these
 * helpers never allocate anything that must outlive the call.
 */

private val LAYOUT_INT = ValueLayout.JAVA_INT
private val LAYOUT_BYTE = ValueLayout.JAVA_BYTE
private val LAYOUT_ADDR = ValueLayout.ADDRESS

// ── Basic appends ────────────────────────────────────────────────────────────

/** Append a string-typed basic value ([DBusBindings.DBUS_TYPE_STRING] or `OBJECT_PATH`). */
internal fun DBusBindings.appendString(call: Arena, iter: MemorySegment, type: Byte, value: String) {
    val strSeg = call.allocateUtf8(value)
    val ptrBuf = call.allocate(LAYOUT_ADDR)
    ptrBuf.set(LAYOUT_ADDR, 0, strSeg)
    handle("dbus_message_iter_append_basic").invokeExact(iter, type.toInt(), ptrBuf) as Int
}

internal fun DBusBindings.appendUint32(call: Arena, iter: MemorySegment, value: Int) {
    val buf = call.allocate(LAYOUT_INT)
    buf.set(LAYOUT_INT, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusBindings.DBUS_TYPE_UINT32.toInt(), buf) as Int
}

internal fun DBusBindings.appendInt32(call: Arena, iter: MemorySegment, value: Int) {
    val buf = call.allocate(LAYOUT_INT)
    buf.set(LAYOUT_INT, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusBindings.DBUS_TYPE_INT32.toInt(), buf) as Int
}

internal fun DBusBindings.appendByte(call: Arena, iter: MemorySegment, value: Byte) {
    val buf = call.allocate(LAYOUT_BYTE)
    buf.set(LAYOUT_BYTE, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusBindings.DBUS_TYPE_BYTE.toInt(), buf) as Int
}

internal fun DBusBindings.appendBool(call: Arena, iter: MemorySegment, value: Boolean) {
    // dbus_bool_t is 4 bytes on the wire, not 1.
    val buf = call.allocate(LAYOUT_INT)
    buf.set(LAYOUT_INT, 0, if (value) 1 else 0)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusBindings.DBUS_TYPE_BOOLEAN.toInt(), buf) as Int
}

// ── Containers ───────────────────────────────────────────────────────────────

internal fun DBusBindings.openContainer(parent: MemorySegment, type: Byte, signature: MemorySegment, sub: MemorySegment) {
    handle("dbus_message_iter_open_container").invokeExact(parent, type.toInt(), signature, sub) as Int
}

internal fun DBusBindings.closeContainer(parent: MemorySegment, sub: MemorySegment) {
    handle("dbus_message_iter_close_container").invokeExact(parent, sub) as Int
}

private fun DBusBindings.scratchIter(call: Arena): MemorySegment = call.allocate(messageIterLayout)

// ── Variant + dict-entry appends (a{sv} hint dictionary) ─────────────────────

internal fun DBusBindings.appendVariantString(call: Arena, parent: MemorySegment, value: String) {
    val sig = call.allocateUtf8("s")
    val variant = scratchIter(call)
    openContainer(parent, DBusBindings.DBUS_TYPE_VARIANT, sig, variant)
    appendString(call, variant, DBusBindings.DBUS_TYPE_STRING, value)
    closeContainer(parent, variant)
}

internal fun DBusBindings.appendVariantByte(call: Arena, parent: MemorySegment, value: Byte) {
    val sig = call.allocateUtf8("y")
    val variant = scratchIter(call)
    openContainer(parent, DBusBindings.DBUS_TYPE_VARIANT, sig, variant)
    appendByte(call, variant, value)
    closeContainer(parent, variant)
}

internal fun DBusBindings.appendDictString(call: Arena, dict: MemorySegment, key: String, value: String) {
    val entry = scratchIter(call)
    openContainer(dict, DBusBindings.DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entry)
    appendString(call, entry, DBusBindings.DBUS_TYPE_STRING, key)
    appendVariantString(call, entry, value)
    closeContainer(dict, entry)
}

internal fun DBusBindings.appendDictByte(call: Arena, dict: MemorySegment, key: String, value: Byte) {
    val entry = scratchIter(call)
    openContainer(dict, DBusBindings.DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entry)
    appendString(call, entry, DBusBindings.DBUS_TYPE_STRING, key)
    appendVariantByte(call, entry, value)
    closeContainer(dict, entry)
}

/**
 * Append the freedesktop `image-data` hint: a variant of `(iiibiiay)` --
 * width, height, rowstride, has_alpha, bits_per_sample, channels, and the raw
 * non-premultiplied RGBA bytes (red first, one row of `width*4` bytes after
 * another). This is how a notification carries an inline image without the
 * server reading a file path.
 */
internal fun DBusBindings.appendDictImageData(call: Arena, dict: MemorySegment, key: String, image: RgbaImage) {
    val entry = scratchIter(call)
    openContainer(dict, DBusBindings.DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entry)
    appendString(call, entry, DBusBindings.DBUS_TYPE_STRING, key)

    val variantSig = call.allocateUtf8("(iiibiiay)")
    val variant = scratchIter(call)
    openContainer(entry, DBusBindings.DBUS_TYPE_VARIANT, variantSig, variant)

    val struct = scratchIter(call)
    openContainer(variant, DBusBindings.DBUS_TYPE_STRUCT, MemorySegment.NULL, struct)
    appendInt32(call, struct, image.width)
    appendInt32(call, struct, image.height)
    appendInt32(call, struct, image.width * 4)   // rowstride: tightly packed RGBA
    appendBool(call, struct, true)               // has_alpha
    appendInt32(call, struct, 8)                 // bits_per_sample
    appendInt32(call, struct, 4)                 // channels (RGBA)

    val byteSig = call.allocateUtf8("y")
    val byteArr = scratchIter(call)
    openContainer(struct, DBusBindings.DBUS_TYPE_ARRAY, byteSig, byteArr)
    // Append the bytes one at a time -- the fixed-array helper isn't in the
    // LOAD_SET. A 64x64 RGBA icon is 16 KB, well inside the budget for a
    // per-byte append on an infrequent operation.
    val byteBuf = call.allocate(LAYOUT_BYTE)
    for (b in image.rgba) {
        byteBuf.set(LAYOUT_BYTE, 0, b)
        handle("dbus_message_iter_append_basic").invokeExact(byteArr, DBusBindings.DBUS_TYPE_BYTE.toInt(), byteBuf) as Int
    }
    closeContainer(struct, byteArr)

    closeContainer(variant, struct)
    closeContainer(entry, variant)
    closeContainer(dict, entry)
}

// ── Reads ────────────────────────────────────────────────────────────────────

/** Read a uint32/int32 at the iterator's cursor, or null if it isn't one. */
internal fun DBusBindings.readUint32(call: Arena, iter: MemorySegment): Int? {
    val argType = handle("dbus_message_iter_get_arg_type").invokeExact(iter) as Int
    if (argType.toByte() != DBusBindings.DBUS_TYPE_UINT32 &&
        argType.toByte() != DBusBindings.DBUS_TYPE_INT32
    ) {
        return null
    }
    val out = call.allocate(LAYOUT_INT)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    return out.get(LAYOUT_INT, 0)
}

/** Read a string at the iterator's cursor, or null if it isn't one. */
internal fun DBusBindings.readString(call: Arena, iter: MemorySegment): String? {
    val argType = handle("dbus_message_iter_get_arg_type").invokeExact(iter) as Int
    if (argType.toByte() != DBusBindings.DBUS_TYPE_STRING &&
        argType.toByte() != DBusBindings.DBUS_TYPE_OBJECT_PATH
    ) {
        return null
    }
    val out = call.allocate(LAYOUT_ADDR)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    val ptr = out.get(LAYOUT_ADDR, 0)
    return if (ptr.address() == 0L) null else ptr.reinterpret(Long.MAX_VALUE).getString(0)
}

/** Read the result of a message accessor that returns a `const char *` (member/interface). */
internal fun DBusBindings.readMessageString(symbol: String, msg: MemorySegment): String? {
    val ptr = handle(symbol).invokeExact(msg) as MemorySegment
    return if (ptr.address() == 0L) null else ptr.reinterpret(Long.MAX_VALUE).getString(0)
}

/**
 * A decoded RGBA image ready for the `image-data` hint: tightly packed,
 * non-premultiplied, red byte first, `height` rows of `width*4` bytes.
 */
internal class RgbaImage(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)
