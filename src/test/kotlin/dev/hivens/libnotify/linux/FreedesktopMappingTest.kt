package dev.hivens.libnotify.linux

import dev.hivens.libnotify.DismissReason
import dev.hivens.libnotify.Timeout
import dev.hivens.libnotify.Urgency
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure mappings between the libnotify model and the freedesktop wire values.
 * These never touch D-Bus, so they run on any CI box.
 */
class FreedesktopMappingTest {

    @Test
    fun `capabilities reflect the server's advertised set`() {
        val caps = FreedesktopNotifier.parseCapabilities(
            listOf("body", "actions", "body-markup", "icon-static"),
        )
        caps.actions shouldBe true
        caps.maxActions shouldBe Int.MAX_VALUE
        caps.bodyMarkup shouldBe true
        caps.icons shouldBe true
        // freedesktop always supports these regardless of the advertised set.
        caps.replace shouldBe true
        caps.closeEvents shouldBe true
    }

    @Test
    fun `a server without actions reports zero max actions`() {
        val caps = FreedesktopNotifier.parseCapabilities(listOf("body"))
        caps.actions shouldBe false
        caps.maxActions shouldBe 0
        caps.bodyMarkup shouldBe false
    }

    @Test
    fun `a null capability set falls back to the optimistic baseline`() {
        val caps = FreedesktopNotifier.parseCapabilities(null)
        caps.actions shouldBe true
        caps.replace shouldBe true
        caps.closeEvents shouldBe true
        caps.urgency shouldBe true
    }

    @Test
    fun `urgency maps to the spec byte`() {
        FreedesktopNotifier.urgencyByte(Urgency.LOW) shouldBe 0.toByte()
        FreedesktopNotifier.urgencyByte(Urgency.NORMAL) shouldBe 1.toByte()
        FreedesktopNotifier.urgencyByte(Urgency.CRITICAL) shouldBe 2.toByte()
    }

    @Test
    fun `expire timeout maps to the spec int`() {
        FreedesktopNotifier.expireMillis(Timeout.ServerDefault) shouldBe -1
        FreedesktopNotifier.expireMillis(Timeout.Never) shouldBe 0
        FreedesktopNotifier.expireMillis(Timeout.After(4000)) shouldBe 4000
    }

    @Test
    fun `close reason codes map to DismissReason`() {
        FreedesktopNotifier.mapReason(1) shouldBe DismissReason.EXPIRED
        FreedesktopNotifier.mapReason(2) shouldBe DismissReason.DISMISSED_BY_USER
        FreedesktopNotifier.mapReason(3) shouldBe DismissReason.CLOSED
        FreedesktopNotifier.mapReason(99) shouldBe DismissReason.UNKNOWN
    }
}
