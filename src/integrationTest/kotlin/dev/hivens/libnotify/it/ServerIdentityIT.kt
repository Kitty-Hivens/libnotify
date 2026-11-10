package dev.hivens.libnotify.it

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.NotificationEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * A server's notification id means something only to that server. These pin who
 * may speak for a notification: the server that issued its id, and nobody else,
 * not even the next server to own the service.
 */
internal class ServerIdentityIT : BusTest() {

    @Test
    fun `a client that is not the server cannot fake a click`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("t", tag = "t")) shouldNotBe null
        val id = server.awaitNotify().id
        // From the real object path and interface: only the sender gives it away.
        Stranger().use { it.emitActionInvoked(id, "default") }
        // Two senders give no ordering guarantee, so this one needs a time window.
        events.assertNone()
        server.click(id)
        events.next() shouldBe NotificationEvent.Activated("t")
    }

    @Test
    fun `a click on an action the notification never offered is not passed on`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(Notification(title = "t", tag = "t", actions = listOf(NotificationAction("open", "Open")))) shouldNotBe null
        val id = server.awaitNotify().id
        server.click(id, "install-update")
        server.click(id, "open")
        events.next() shouldBe NotificationEvent.ActionInvoked("t", "open")
    }

    @Test
    fun `a restarted server is not asked to replace an id it never issued`() {
        val first = daemon()
        val notifier = notifier()
        notifier.notify(titled("v1", tag = "t")) shouldNotBe null
        first.awaitNotify()
        stop(first)
        val second = daemon()
        notifier.notify(titled("v2", tag = "t")) shouldNotBe null
        second.awaitNotify().replacesId shouldBe 0
        // The new server's own id is reused for the next replacement of the tag.
        val current = second.awaitNotifyAfter { notifier.notify(titled("v3", tag = "t")) }
        current.replacesId shouldNotBe 0
    }

    @Test
    fun `after a restart a reused id does not report as our old notification`() {
        val first = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("old", tag = "old")) shouldNotBe null
        val id = first.awaitNotify().id
        stop(first)
        // The restarted server hands our next post a fresh number, while the old
        // one now belongs to some other application's notification.
        val second = daemon(firstId = id + 1)
        notifier.notify(titled("new", tag = "new")) shouldNotBe null
        val ours = second.awaitNotify().id
        ours shouldNotBe id
        second.click(id)
        second.click(ours)
        events.next() shouldBe NotificationEvent.Activated("new")
    }

    @Test
    fun `cancelling a notification of a server that went away asks nobody`() {
        val first = daemon()
        val notifier = notifier()
        val handle = notifier.notify(titled("old", tag = "old")) ?: error("the post failed, so this proves nothing")
        first.awaitNotify()
        stop(first)
        val second = daemon()
        notifier.cancel(handle) shouldBe false
        second.assertNoCloseRequest()
    }

    @Test
    fun `body escaping follows a restarted server`() {
        val first = daemon(capabilities = listOf("body", "body-markup"))
        val notifier = notifier()
        notifier.notify(Notification(title = "t", body = "a<b")) shouldNotBe null
        first.awaitNotify().body shouldBe "a&lt;b"
        stop(first)
        val second = daemon(capabilities = listOf("body"))
        notifier.notify(Notification(title = "t", body = "a<b")) shouldNotBe null
        second.awaitNotify().body shouldBe "a<b"
    }

    private fun FakeDaemon.awaitNotifyAfter(post: () -> Unit): ReceivedNotify {
        post()
        return awaitNotify()
    }
}
