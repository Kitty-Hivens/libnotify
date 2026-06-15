package dev.hivens.libnotify.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Panama bindings to the Windows Runtime activation surface in `combase.dll`,
 * plus the COM plumbing the [ToastNotifier] needs: GUID and HSTRING marshalling,
 * vtable navigation, [queryInterface]/[release], and synthesis of the COM
 * event-handler objects that receive toast activation callbacks.
 *
 * **No projection, no WRL.** Every WinRT call is a raw indirect call through an
 * interface's vtable -- `iface` points at an object whose first field is a
 * vtable pointer, method *i* lives at `vtable[i]`, and is invoked as
 * `HRESULT method(thisPtr, args...)`. [vtableFn] reads the slot; the call site
 * builds a [FunctionDescriptor] for the method's exact ABI and downcalls it.
 *
 * **Event handlers.** Toast `add_Activated` / `add_Dismissed` take an
 * `ITypedEventHandler<...>` COM object. The parameterized IIDs of those handler
 * types are computed (not constant) and impractical to reproduce by hand, so
 * the synthesised objects use a *lenient* `QueryInterface` that hands back the
 * same object for any requested IID -- the runtime only needs an object whose
 * `Invoke` (vtable slot 3) it can call. The object identity of the activated
 * notification is recovered from the `sender` argument's `Tag`, whose interface
 * IID ([IID_TOAST_NOTIFICATION2]) is fixed, so routing never depends on a
 * guessed IID.
 *
 * GUIDs and vtable indices are taken from the Windows SDK `windows.ui.notifications.idl`
 * (and `windows.data.xml.dom.idl`). HRESULT `S_OK` is 0; negative values are
 * failures.
 */
internal class WinRtBindings private constructor(
    val arena: Arena,
    val linker: Linker,
    private val handles: Map<String, MethodHandle>,
    private val guidCache: MutableMap<String, MemorySegment>,
) {
    fun handle(name: String): MethodHandle =
        handles[name] ?: error("WinRT handle not loaded: $name. Add to LOAD_SET in WinRtBindings.load.")

    // ── GUID ────────────────────────────────────────────────────────────────

    /**
     * Resolve a canonical GUID string ("XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX")
     * to a 16-byte little-endian struct in the long-lived arena. Cached. The
     * memory layout is Data1 (u32 LE), Data2/Data3 (u16 LE), Data4[8] in
     * written order -- which native little-endian `set` calls produce directly.
     */
    fun guid(value: String): MemorySegment = guidCache.getOrPut(value) {
        val hex = value.replace("-", "")
        require(hex.length == 32) { "malformed GUID: $value" }
        val seg = arena.allocate(16)
        seg.set(ValueLayout.JAVA_INT, 0, hex.substring(0, 8).toLong(16).toInt())
        seg.set(ValueLayout.JAVA_SHORT, 4, hex.substring(8, 12).toInt(16).toShort())
        seg.set(ValueLayout.JAVA_SHORT, 6, hex.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) {
            seg.set(ValueLayout.JAVA_BYTE, (8 + i).toLong(), hex.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
        }
        seg
    }

    // ── HSTRING ─────────────────────────────────────────────────────────────

    /** Create an HSTRING from a JVM string. The source UTF-16 buffer is copied, so [scratch] may free after. */
    fun createHString(scratch: Arena, s: String): MemorySegment {
        val buf = scratch.allocate((s.length + 1) * 2L)
        for (i in s.indices) buf.set(ValueLayout.JAVA_SHORT, i * 2L, s[i].code.toShort())
        buf.set(ValueLayout.JAVA_SHORT, s.length * 2L, 0)
        val out = scratch.allocate(ValueLayout.ADDRESS)
        val hr = handle("WindowsCreateString").invokeExact(buf, s.length, out) as Int
        return if (hr < 0) MemorySegment.NULL else out.get(ValueLayout.ADDRESS, 0)
    }

    fun deleteHString(h: MemorySegment) {
        if (h.address() != 0L) runCatching { handle("WindowsDeleteString").invokeExact(h) as Int }
    }

    /** Read an HSTRING back into a JVM string. */
    fun readHString(scratch: Arena, h: MemorySegment): String {
        if (h.address() == 0L) return ""
        val lenOut = scratch.allocate(ValueLayout.JAVA_INT)
        val buf = handle("WindowsGetStringRawBuffer").invokeExact(h, lenOut) as MemorySegment
        val len = lenOut.get(ValueLayout.JAVA_INT, 0)
        if (buf.address() == 0L || len <= 0) return ""
        val wide = buf.reinterpret(len * 2L)
        val chars = CharArray(len)
        for (i in 0 until len) chars[i] = (wide.get(ValueLayout.JAVA_SHORT, i * 2L).toInt() and 0xFFFF).toChar()
        return String(chars)
    }

    // ── vtable / COM ──────────────────────────────────────────────────────────

    /** The function pointer at `vtable[index]` of a COM interface pointer. */
    fun vtableFn(iface: MemorySegment, index: Int): MemorySegment {
        val vtable = iface.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0)
        return vtable.reinterpret((index + 1) * 8L).getAtIndex(ValueLayout.ADDRESS, index.toLong())
    }

    /** Build a downcall handle for a vtable method's exact ABI. */
    fun downcall(fn: MemorySegment, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(fn, descriptor)

    /** `IUnknown::QueryInterface`. Returns the requested interface pointer or null on E_NOINTERFACE. */
    fun queryInterface(iface: MemorySegment, iid: String, scratch: Arena): MemorySegment? {
        val h = downcall(vtableFn(iface, 0),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
        val out = scratch.allocate(ValueLayout.ADDRESS)
        val hr = h.invokeExact(iface, guid(iid), out) as Int
        if (hr < 0) return null
        val p = out.get(ValueLayout.ADDRESS, 0)
        return if (p.address() == 0L) null else p
    }

    /** `IUnknown::Release`. Safe on NULL. */
    fun release(iface: MemorySegment) {
        if (iface.address() == 0L) return
        runCatching {
            downcall(vtableFn(iface, 2), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                .invokeExact(iface) as Int
        }
    }

    /**
     * Synthesise a COM event-handler object: a `{ vtable* }` whose vtable is
     * `[QueryInterface, AddRef, Release, invoke]` -- the first three the shared
     * lenient stubs, the fourth the caller's `Invoke` upcall. Lives in the
     * long-lived [arena]; the runtime's AddRef/Release are no-ops, so the object
     * persists for the process and never needs reclaiming.
     */
    fun makeEventHandler(invokeStub: MemorySegment): MemorySegment {
        val vtable = arena.allocate(ValueLayout.ADDRESS, 4)
        vtable.setAtIndex(ValueLayout.ADDRESS, 0, qiStub)
        vtable.setAtIndex(ValueLayout.ADDRESS, 1, addRefStub)
        vtable.setAtIndex(ValueLayout.ADDRESS, 2, releaseStub)
        vtable.setAtIndex(ValueLayout.ADDRESS, 3, invokeStub)
        val obj = arena.allocate(ValueLayout.ADDRESS, 1)
        obj.set(ValueLayout.ADDRESS, 0, vtable)
        return obj
    }

    // Shared lenient IUnknown stubs, built once over the long-lived arena.
    private val qiStub: MemorySegment
    private val addRefStub: MemorySegment
    private val releaseStub: MemorySegment

    init {
        val lookup = MethodHandles.lookup()
        qiStub = linker.upcallStub(
            lookup.findStatic(WinRtBindings::class.java, "qiEntry",
                MethodType.methodType(Int::class.javaPrimitiveType!!,
                    MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java)),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            arena,
        )
        addRefStub = linker.upcallStub(
            lookup.findStatic(WinRtBindings::class.java, "refEntry",
                MethodType.methodType(Int::class.javaPrimitiveType!!, MemorySegment::class.java)),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            arena,
        )
        releaseStub = linker.upcallStub(
            lookup.findStatic(WinRtBindings::class.java, "refEntry",
                MethodType.methodType(Int::class.javaPrimitiveType!!, MemorySegment::class.java)),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            arena,
        )
    }

    companion object {
        // ── COM identity ──────────────────────────────────────────────────────
        const val IID_UNKNOWN = "00000000-0000-0000-C000-000000000046"
        const val IID_INSPECTABLE = "AF86E2E0-B12D-4C6A-9C5A-D7AA65101E90"

        // ── Windows.UI.Notifications (SDK windows.ui.notifications.idl) ─────────
        const val IID_TOAST_MANAGER_STATICS = "50AC103F-D235-4598-BBEF-98FE4D1A3AD4"
        const val IID_TOAST_NOTIFIER = "75927B93-03F3-41EC-91D3-6E5BAC1B38E7"
        const val IID_TOAST_NOTIFICATION_FACTORY = "04124B20-82C6-4229-B109-FD9ED4662B53"
        const val IID_TOAST_NOTIFICATION = "997E2675-059E-4E60-8B06-1760917C8B80"
        const val IID_TOAST_NOTIFICATION2 = "9DFB9FD1-143A-490E-90BF-B9FBA7132DE7"
        const val IID_TOAST_ACTIVATED_ARGS = "E3BF92F3-C197-436F-8265-0625824F8DAC"
        const val IID_TOAST_DISMISSED_ARGS = "3F89D935-D9CB-4538-A0F0-FFE7659938F8"

        // ── Windows.Data.Xml.Dom ───────────────────────────────────────────────
        const val IID_XML_DOCUMENT = "F7F3A506-1E87-42D6-BCFB-B8C809FA5494"
        const val IID_XML_DOCUMENT_IO = "6CD0E74E-EE65-4489-9EBF-CA43E87BA637"

        // ── Activatable class ids ──────────────────────────────────────────────
        const val CLASS_TOAST_MANAGER = "Windows.UI.Notifications.ToastNotificationManager"
        const val CLASS_TOAST_NOTIFICATION = "Windows.UI.Notifications.ToastNotification"
        const val CLASS_XML_DOCUMENT = "Windows.Data.Xml.Dom.XmlDocument"

        // ── vtable indices (after the 6 IInspectable slots) ────────────────────
        const val IDX_QUERY_INTERFACE = 0
        const val IDX_CREATE_TOAST_NOTIFIER_WITH_ID = 7
        const val IDX_SHOW = 6
        const val IDX_HIDE = 7
        const val IDX_CREATE_TOAST_NOTIFICATION = 6
        const val IDX_PUT_TAG = 6
        const val IDX_GET_TAG = 7
        const val IDX_ADD_DISMISSED = 9
        const val IDX_ADD_ACTIVATED = 11
        const val IDX_GET_ARGUMENTS = 6
        const val IDX_GET_REASON = 6
        const val IDX_LOAD_XML = 6

        // ── RoInitialize concurrency models ────────────────────────────────────
        const val RO_INIT_MULTITHREADED = 1

        /** ToastDismissalReason (SDK enum). */
        const val DISMISS_USER_CANCELED = 0
        const val DISMISS_APPLICATION_HIDDEN = 1
        const val DISMISS_TIMED_OUT = 2

        private val LOAD_SET: List<Triple<String, java.lang.foreign.MemoryLayout?, List<java.lang.foreign.MemoryLayout>>> = listOf(
            Triple("RoInitialize", ValueLayout.JAVA_INT, listOf(ValueLayout.JAVA_INT)),
            Triple("RoUninitialize", null, emptyList()),
            Triple("RoGetActivationFactory", ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("RoActivateInstance", ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("WindowsCreateString", ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
            Triple("WindowsDeleteString", ValueLayout.JAVA_INT, listOf(ValueLayout.ADDRESS)),
            Triple("WindowsGetStringRawBuffer", ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
        )

        /**
         * Load `combase.dll` and bind the WinRT activation entry points. Returns
         * null off Windows or when a symbol is missing -- the notifier degrades
         * to "no notifications", same null-as-degrade path as the other backends.
         */
        fun load(): WinRtBindings? {
            val arena = Arena.ofShared()
            val lookup = runCatching { SymbolLookup.libraryLookup("combase", arena) }.getOrNull() ?: run {
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
            return WinRtBindings(arena, linker, handles, HashMap())
        }

        private val E_NOINTERFACE = 0x80004002.toInt()
        private val IID_IMARSHAL_BYTES = guidBytes("00000003-0000-0000-C000-000000000046")

        /**
         * Near-lenient `QueryInterface` for the synthesised event handler.
         * Denies `IMarshal` so the runtime never tries to custom-marshal this
         * stub across apartments, and hands back the same object for every
         * other IID -- IUnknown, IAgileObject (claiming free-threaded, so the
         * handler is invoked in-process and no marshaling is attempted), and
         * the parameterized `ITypedEventHandler` IID we cannot reproduce by
         * hand. Returning `self` for everything-but-IMarshal is what lets the
         * runtime accept and call `Invoke` (vtable slot 3) without us knowing
         * the computed handler IID.
         */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun qiEntry(self: MemorySegment, riid: MemorySegment, ppv: MemorySegment): Int {
            val req = ByteArray(16)
            MemorySegment.copy(riid.reinterpret(16), ValueLayout.JAVA_BYTE, 0, req, 0, 16)
            val outSlot = ppv.reinterpret(ValueLayout.ADDRESS.byteSize())
            if (req.contentEquals(IID_IMARSHAL_BYTES)) {
                outSlot.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL)
                return E_NOINTERFACE
            }
            outSlot.set(ValueLayout.ADDRESS, 0, self)
            return 0  // S_OK
        }

        /** Canonical-GUID-string -> the 16-byte little-endian COM struct as a JVM byte array. */
        private fun guidBytes(value: String): ByteArray {
            val hex = value.replace("-", "")
            require(hex.length == 32) { "malformed GUID: $value" }
            val b = ByteArray(16)
            val d1 = hex.substring(0, 8).toLong(16)
            b[0] = (d1 and 0xFF).toByte(); b[1] = ((d1 ushr 8) and 0xFF).toByte()
            b[2] = ((d1 ushr 16) and 0xFF).toByte(); b[3] = ((d1 ushr 24) and 0xFF).toByte()
            val d2 = hex.substring(8, 12).toInt(16)
            b[4] = (d2 and 0xFF).toByte(); b[5] = ((d2 ushr 8) and 0xFF).toByte()
            val d3 = hex.substring(12, 16).toInt(16)
            b[6] = (d3 and 0xFF).toByte(); b[7] = ((d3 ushr 8) and 0xFF).toByte()
            for (i in 0 until 8) b[8 + i] = hex.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte()
            return b
        }

        /** AddRef / Release: a constant non-zero refcount -- the object lives for the process. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun refEntry(self: MemorySegment): Int = 1
    }
}
