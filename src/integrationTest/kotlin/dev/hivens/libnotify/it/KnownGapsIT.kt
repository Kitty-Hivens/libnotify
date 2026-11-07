package dev.hivens.libnotify.it

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.NotifierConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The contract as it should be, for behaviour that does not meet it yet. See
 * [knownGap]: each entry turns red when its gap closes, and then moves next to the
 * tests for that area as a plain test. Everything outside `gap { }` is an
 * ordinary assertion, so a broken setup fails here instead of passing as a gap.
 */
internal class KnownGapsIT : BusTest() {

    @Test
    fun `a client that is not the server cannot fake a click`() = knownGap("signals are not checked for their sender") {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("t", tag = "t")) shouldNotBe null
        val id = server.awaitNotify().id
        Stranger().use { it.emitActionInvoked(id, "default") }
        // Two senders give no ordering guarantee, so this one needs a time window.
        gap { events.assertNone() }
    }

    @Test
    fun `a click on an action the notification never offered is ignored`() = knownGap("action ids are not checked against the post") {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(Notification(title = "t", tag = "t", actions = listOf(NotificationAction("open", "Open")))) shouldNotBe null
        val id = server.awaitNotify().id
        server.click(id, "install-update")
        server.click(id)
        gap { events.next() shouldBe NotificationEvent.Activated("t") }
    }

    @Test
    fun `a restarted server is not asked to replace an id it never issued`() = knownGap("server ids survive a daemon restart") {
        val first = daemon()
        val notifier = notifier()
        notifier.notify(titled("v1", tag = "t")) shouldNotBe null
        first.awaitNotify()
        stop(first)
        val second = daemon()
        notifier.notify(titled("v2", tag = "t")) shouldNotBe null
        val got = second.awaitNotify()
        gap { got.replacesId shouldBe 0 }
    }

    @Test
    fun `after a restart a reused id does not report as our old notification`() = knownGap("server ids survive a daemon restart") {
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
        gap { events.next() shouldBe NotificationEvent.Activated("new") }
    }

    @Test
    fun `body escaping follows a restarted server`() = knownGap("capabilities are cached across a daemon restart") {
        val first = daemon(capabilities = listOf("body", "body-markup"))
        val notifier = notifier()
        notifier.notify(titled("t")) shouldNotBe null
        first.awaitNotify()
        stop(first)
        val second = daemon(capabilities = listOf("body"))
        notifier.notify(Notification(title = "t", body = "a<b")) shouldNotBe null
        val got = second.awaitNotify()
        gap { got.body shouldBe "a<b" }
    }

    @Test
    fun `a NUL in the title does not cut off the rest`() = knownGap("C strings end at the first NUL") {
        val server = daemon()
        notifier().notify(titled("before\u0000after")) shouldNotBe null
        val got = server.awaitNotify()
        gap { got.summary shouldContain "after" }
    }

    @Test
    fun `a NUL in the body does not cut off the rest`() = knownGap("C strings end at the first NUL") {
        val server = daemon()
        notifier().notify(Notification(title = "t", body = "before\u0000after")) shouldNotBe null
        val got = server.awaitNotify()
        gap { got.body shouldContain "after" }
    }

    @Test
    fun `an explicit icon name is not hidden by the default icon bytes`() = knownGap("image-data outranks app_icon on the server") {
        val server = daemon()
        val notifier = notifier(NotifierConfig(appName = "a", defaultIconBytes = pngOf(0xFF000000.toInt())))
        notifier.notify(Notification(title = "t", iconName = "dialog-warning")) shouldNotBe null
        val got = server.awaitNotify()
        got.appIcon shouldBe "dialog-warning"
        gap { got.image shouldBe null }
    }

    @Test
    fun `a user tag cannot collide with a generated id`() = knownGap("generated ids are predictable") {
        val server = daemon()
        val notifier = notifier()
        val generated = notifier.notify(titled("generated")) ?: error("the post failed, so this proves nothing")
        server.awaitNotify()
        notifier.notify(titled("tagged", tag = generated.id)) shouldNotBe null
        val got = server.awaitNotify()
        gap { got.replacesId shouldBe 0 }
    }

    @Test
    fun `generated ids are not reused by another notifier`() = knownGap("each notifier counts from zero") {
        daemon()
        val first = notifier().notify(titled("t")) ?: error("the first post failed, so this proves nothing")
        val second = notifier().notify(titled("t")) ?: error("the second post failed, so this proves nothing")
        gap { first.id shouldNotBe second.id }
    }

    @Test
    fun `a handle cannot cancel another notifier's notification`() = knownGap("a handle is only an id, not bound to its notifier") {
        val server = daemon()
        val owner = notifier()
        val other = notifier()
        val handle = owner.notify(titled("owner's")) ?: error("the post failed, so this proves nothing")
        server.awaitNotify()
        other.notify(titled("other's")) ?: error("the post failed, so this proves nothing")
        server.awaitNotify()
        val cancelled = other.cancel(handle)
        gap { cancelled shouldBe false }
    }
}
