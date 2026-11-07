package dev.hivens.libnotify

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
        shouldThrow<IllegalArgumentException> { NotificationAction(DEFAULT_ACTION_ID, "Open") }
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
    fun `a Notification survives a round trip through its builder`() {
        val original = Notification(
            title = "t",
            body = "b",
            iconBytes = byteArrayOf(1),
            iconName = "i",
            urgency = Urgency.CRITICAL,
            timeout = Timeout.After(1_000),
            actions = listOf(NotificationAction("a", "A")),
            category = "c",
            tag = "g",
        )
        original.toBuilder().build() shouldBe original
        original.toBuilder().title("changed").build().title shouldBe "changed"
    }

    @Test
    fun `the Notification builder checks like the constructor`() {
        shouldThrow<IllegalArgumentException> { Notification.Builder(" ").build() }
        shouldThrow<IllegalArgumentException> { Notification.Builder("t").tag("").build() }
    }

    @Test
    fun `the Notification builder copies the action list it is given`() {
        val actions = mutableListOf(NotificationAction("a", "A"))
        val built = Notification.Builder("t").actions(actions).build()
        actions.add(NotificationAction("b", "B"))
        built.actions.size shouldBe 1
    }

    @Test
    fun `a NotifierConfig survives a round trip through its builder`() {
        val original = NotifierConfig("App", "com.example.App", "icon", byteArrayOf(1))
        original.toBuilder().build() shouldBe original
        NotifierConfig.Builder("App").appId("x").build().appId shouldBe "x"
    }

    @Test
    fun `toString shows icon sizes, not array identities`() {
        Notification(title = "t", iconBytes = byteArrayOf(1, 2)).toString() shouldContain "iconBytes=2 bytes"
        NotifierConfig(appName = "a", defaultIconBytes = byteArrayOf(1)).toString() shouldContain "defaultIconBytes=1 bytes"
    }

    @Test
    fun `NotifierConfig equality is icon content-based`() {
        val a = NotifierConfig(appName = "App", defaultIconBytes = byteArrayOf(9, 9))
        val b = NotifierConfig(appName = "App", defaultIconBytes = byteArrayOf(9, 9))
        (a == b) shouldBe true
        a.hashCode() shouldBe b.hashCode()
    }
}
