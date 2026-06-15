package dev.hivens.libnotify

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure-data sanity for the notification model. Every backend consumes these
 * verbatim, so a misshape here propagates to all three.
 */
class NotificationModelTest {

    @Test
    fun `Notification rejects a blank title`() {
        shouldThrow<IllegalArgumentException> { Notification(title = "  ") }
    }

    @Test
    fun `Notification rejects an empty icon array`() {
        shouldThrow<IllegalArgumentException> { Notification(title = "x", iconBytes = ByteArray(0)) }
    }

    @Test
    fun `Notification rejects a blank tag`() {
        shouldThrow<IllegalArgumentException> { Notification(title = "x", tag = "") }
    }

    @Test
    fun `Notification rejects duplicate action ids`() {
        shouldThrow<IllegalArgumentException> {
            Notification(
                title = "x",
                actions = listOf(
                    NotificationAction("a", "A"),
                    NotificationAction("a", "A again"),
                ),
            )
        }
    }

    @Test
    fun `Notification defaults are sane`() {
        val n = Notification(title = "hello")
        n.body shouldBe ""
        n.urgency shouldBe Urgency.NORMAL
        n.timeout shouldBe Timeout.ServerDefault
        n.actions shouldBe emptyList()
    }

    @Test
    fun `Notification equality is icon content-based`() {
        val a = Notification(title = "t", iconBytes = byteArrayOf(1, 2, 3))
        val b = Notification(title = "t", iconBytes = byteArrayOf(1, 2, 3))
        (a == b) shouldBe true
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun `NotificationAction rejects the reserved default id`() {
        shouldThrow<IllegalArgumentException> { NotificationAction(NotificationAction.DEFAULT_ID, "Open") }
    }

    @Test
    fun `NotificationAction rejects blanks`() {
        shouldThrow<IllegalArgumentException> { NotificationAction("", "label") }
        shouldThrow<IllegalArgumentException> { NotificationAction("id", "  ") }
    }

    @Test
    fun `Timeout After rejects non-positive millis`() {
        shouldThrow<IllegalArgumentException> { Timeout.After(0) }
        shouldThrow<IllegalArgumentException> { Timeout.After(-5) }
    }

    @Test
    fun `NotifierConfig rejects a blank app name`() {
        shouldThrow<IllegalArgumentException> { NotifierConfig(appName = " ") }
    }

    @Test
    fun `NotifierConfig equality is icon content-based`() {
        val a = NotifierConfig(appName = "App", defaultIconBytes = byteArrayOf(9, 9))
        val b = NotifierConfig(appName = "App", defaultIconBytes = byteArrayOf(9, 9))
        (a == b) shouldBe true
        a.hashCode() shouldBe b.hashCode()
    }
}
