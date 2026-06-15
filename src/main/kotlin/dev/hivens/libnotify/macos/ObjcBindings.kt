package dev.hivens.libnotify.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets

/**
 * Panama bindings to the Objective-C runtime + Foundation/AppKit. Loaded once
 * per process via [ObjcBindings.load]; the resulting object holds:
 *
 *   * Method handles for `objc_*` runtime intrinsics (`msgSend` variants,
 *     class/selector lookup, runtime class registration for the delegate
 *     upcall target).
 *   * Class & selector caches -- fast id lookup for the symbols the notifier
 *     backend hits.
 *
 * `objc_msgSend` is C-variadic by spec but Panama's [Linker] needs an exact
 * [FunctionDescriptor] per call shape. One downcall handle is declared per
 * parameter signature the backend uses; add a new variant here, not at the
 * call site, when introducing a new shape -- keeps the ABI surface auditable.
 *
 * **Architecture note.** ARM64 (Apple Silicon) unified `objc_msgSend` for all
 * return types; x86_64 (Intel) splits into `objc_msgSend_stret`/`_fpret` for
 * struct/float returns. The notifier never calls a struct- or float-returning
 * Cocoa method, so plain `objc_msgSend` covers both archs.
 *
 * Adapted from libtray's ObjcBindings -- same runtime, a different slice of
 * Foundation (NSUserNotification rather than NSStatusBar).
 */
internal class ObjcBindings private constructor(
    val arena: Arena,
    private val handles: Map<String, MethodHandle>,
    private val classCache: MutableMap<String, MemorySegment>,
    private val selCache: MutableMap<String, MemorySegment>,
) {

    fun handle(name: String): MethodHandle =
        handles[name] ?: error("ObjC handle not loaded: $name. Add to LOAD_SET in ObjcBindings.load.")

    /** Resolve an Objective-C class by name (cached; stable for the JVM's life). Null if not registered. */
    fun clsOrNull(name: String): MemorySegment? {
        classCache[name]?.let { return it }
        return Arena.ofConfined().use { tmp ->
            val nameSeg = tmp.allocateFrom(name)
            val result = handle("objc_getClass").invokeExact(nameSeg) as MemorySegment
            if (result.address() == 0L) null else result.also { classCache[name] = it }
        }
    }

    /** Resolve a class, asserting it exists (typo / framework-not-loaded is a programmer error). */
    fun cls(name: String): MemorySegment =
        clsOrNull(name) ?: error("objc_getClass returned NULL for '$name'")

    /** Resolve an Objective-C selector by name (cached; `sel_registerName` interns globally). */
    fun sel(name: String): MemorySegment = selCache.getOrPut(name) {
        Arena.ofConfined().use { tmp ->
            val nameSeg = tmp.allocateFrom(name)
            handle("sel_registerName").invokeExact(nameSeg) as MemorySegment
        }
    }

    // ── NSString / NSData helpers ────────────────────────────────────────────

    /** Build an autoreleased `NSString*` from a JVM string. */
    fun nsString(text: String): MemorySegment {
        val cls = cls("NSString")
        val sel = sel("stringWithUTF8String:")
        return Arena.ofConfined().use { tmp ->
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            val seg = tmp.allocate(bytes.size + 1L)
            seg.asByteBuffer().put(bytes)
            seg.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0)
            handle("objc_msgSend_id_id").invokeExact(cls, sel, seg) as MemorySegment
        }
    }

    /** Read an `NSString*` back into a JVM string via `-UTF8String`. Null/empty safe. */
    fun jvmString(nsString: MemorySegment): String? {
        if (nsString.address() == 0L) return null
        val ptr = handle("objc_msgSend_id").invokeExact(nsString, sel("UTF8String")) as MemorySegment
        return if (ptr.address() == 0L) null else ptr.reinterpret(Long.MAX_VALUE).getString(0)
    }

    /** Build an autoreleased `NSData*` from a JVM byte array (AppKit decodes PNGs from it). */
    fun nsData(bytes: ByteArray): MemorySegment {
        val cls = cls("NSData")
        val sel = sel("dataWithBytes:length:")
        return Arena.ofConfined().use { tmp ->
            val seg = tmp.allocate(bytes.size.toLong())
            seg.asByteBuffer().put(bytes)
            handle("objc_msgSend_id_ptr_long").invokeExact(cls, sel, seg, bytes.size.toLong()) as MemorySegment
        }
    }

    companion object {

        /** Objective-C runtime + Foundation (NSUserNotification) + AppKit (NSImage). */
        private val LIBS = listOf(
            "libobjc.A.dylib",
            "/System/Library/Frameworks/Foundation.framework/Foundation",
            "/System/Library/Frameworks/AppKit.framework/AppKit",
        )

        /**
         * Symbols loaded -- runtime intrinsics + the `msgSend` shape variants
         * the backend needs. Naming: `objc_msgSend_<ret>_<arg>...` with
         * `id`=object ptr, `sel`=selector, `ptr`=raw ptr, `long`=NSInteger,
         * `void`=no return. The (receiver, selector) pair is implicit.
         */
        private val LOAD_SET: List<Triple<String, String, FunctionDescriptor>> = listOf(
            Triple("objc_getClass", "objc_getClass",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("sel_registerName", "sel_registerName",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_retain", "objc_retain",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_release", "objc_release",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
            Triple("objc_autoreleasePoolPush", "objc_autoreleasePoolPush",
                FunctionDescriptor.of(ValueLayout.ADDRESS)),
            Triple("objc_autoreleasePoolPop", "objc_autoreleasePoolPop",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),

            // Runtime class registration (delegate upcall target).
            Triple("objc_allocateClassPair", "objc_allocateClassPair",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
            Triple("class_addMethod", "class_addMethod",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_registerClassPair", "objc_registerClassPair",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
            Triple("class_createInstance", "class_createInstance",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),

            // objc_msgSend variants -- one per argument shape.
            Triple("objc_msgSend_id", "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_msgSend_id_id", "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_msgSend_id_id_id", "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_msgSend_id_ptr_long", "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
            Triple("objc_msgSend_void_id", "objc_msgSend",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_msgSend_void_long", "objc_msgSend",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
            Triple("objc_msgSend_void", "objc_msgSend",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_msgSend_long", "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
        )

        /**
         * Load runtime + Foundation + AppKit into a fresh shared arena and bind
         * every symbol in [LOAD_SET]. Returns null when not on macOS (`dlopen`
         * fails) or any required symbol is missing.
         */
        fun load(): ObjcBindings? {
            val arena = Arena.ofShared()
            val lookups = LIBS.mapNotNull { name ->
                runCatching { SymbolLookup.libraryLookup(name, arena) }.getOrNull()
            }
            // libobjc + Foundation are mandatory; AppKit only gates the icon.
            if (lookups.size < 2) {
                arena.close()
                return null
            }
            val linker = Linker.nativeLinker()
            val handles = HashMap<String, MethodHandle>(LOAD_SET.size * 2)
            for ((alias, symbol, descriptor) in LOAD_SET) {
                val sym = lookups.firstNotNullOfOrNull { it.find(symbol).orElse(null) } ?: run {
                    arena.close()
                    return null
                }
                handles[alias] = linker.downcallHandle(sym, descriptor)
            }
            return ObjcBindings(arena, handles, HashMap(), HashMap())
        }
    }
}
