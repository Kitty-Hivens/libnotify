package dev.hivens.libnotify.it

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.NotificationHandle
import dev.hivens.libnotify.NotificationListener
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierCapabilities
import dev.hivens.libnotify.Subscription
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A consumer must be able to stand in a Notifier of its own, to test its code
 * without a notification service. This is the shape such a double takes; if it
 * stops compiling, consumers' tests stop compiling with it. No bus needed.
 */
internal class TestDoubleTest {

    private class FakeNotifier : Notifier {
        @Volatile var closed = false
        override val isOpen: Boolean get() = !closed
        override val capabilities = NotifierCapabilities.Builder().actions(true).maxActions(2).build()
        override fun notify(notification: Notification): NotificationHandle? = null
        override fun cancel(handle: NotificationHandle): Boolean = false
        override fun onEvent(listener: NotificationListener): Subscription = Subscription { }
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `a consumer can implement Notifier as a test double`() {
        val fake: Notifier = FakeNotifier()
        fake.capabilities.actions shouldBe true
        fake.capabilities.maxActions shouldBe 2
        fake.capabilities.urgency shouldBe false
        fake.onEvent { _: NotificationEvent -> }.close()
        fake.use { }
        fake.isOpen shouldBe false
    }

    @Test
    fun `capabilities built from nothing claim nothing`() {
        val none = NotifierCapabilities.Builder().build()
        listOf(none.actions, none.bodyMarkup, none.icons, none.replace, none.closeEvents, none.urgency)
            .all { !it } shouldBe true
        none.maxActions shouldBe 0
    }

    @Test
    fun `capabilities survive a round trip through their builder`() {
        val caps = NotifierCapabilities.Builder()
            .actions(true).maxActions(5).bodyMarkup(true).icons(true).replace(true).closeEvents(true).urgency(true)
            .build()
        caps.toBuilder().build() shouldBe caps
        caps.toBuilder().urgency(false).build().urgency shouldBe false
    }
}
