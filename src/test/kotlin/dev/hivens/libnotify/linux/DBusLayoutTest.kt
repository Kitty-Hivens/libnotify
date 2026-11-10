package dev.hivens.libnotify.linux

import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.OS

/**
 * The scratch buffers libdbus writes into must fit its structs and their
 * alignment. Sizes from `dbus/dbus-message.h` and `dbus/dbus-errors.h` on
 * 64-bit Linux, checked with sizeof against the installed headers.
 */
class DBusLayoutTest {

    private fun bindings(): DBusBindings {
        assumeTrue(OS.current() == OS.LINUX, "libdbus is loaded on Linux only")
        return DBusBindings.load() ?: error("libdbus did not load on a Linux host")
    }

    @Test
    fun `the message iterator scratch holds a DBusMessageIter, aligned for its pointers`() {
        val b = bindings()
        try {
            b.messageIterLayout.byteSize() shouldBeGreaterThanOrEqualTo 72L
            b.messageIterLayout.byteAlignment() shouldBe 8L
        } finally {
            b.arena.close()
        }
    }

    @Test
    fun `the error scratch holds a DBusError, aligned for its pointers`() {
        val b = bindings()
        try {
            b.errorLayout.byteSize() shouldBe 32L
            b.errorLayout.byteAlignment() shouldBe 8L
        } finally {
            b.arena.close()
        }
    }
}
