package dev.hivens.libnotify.it

import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.NotifierConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The contract as it should be, for behaviour that does not meet it yet. See
 * [knownGap]: each entry turns red when its gap closes, and then moves next to the
 * tests for that area as a plain test.
 */
internal class KnownGapsIT : BusTest() {

    @Test
    fun `a client that is not the server cannot fake a click`() = knownGap("signals are not checked for their sender") {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("t"))
        val id = server.awaitNotify().id
        Stranger().use { it.emitActionInvoked(id, "default") }
        events.assertNone()
    }

    @Test
    fun `a click on an action the notification never offered is ignored`() = knownGap("action ids are not checked against the post") {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(Notification(title = "t", actions = listOf(NotificationAction("open", "Open"))))
        server.click(server.awaitNotify().id, "install-update")
        events.assertNone()
    }

    @Test
    fun `a restarted server is not asked to replace an id it never issued`() = knownGap("server ids survive a daemon restart") {
        val first = daemon()
        val notifier = notifier()
        notifier.notify(titled("v1", tag = "t"))
        first.awaitNotify()
        stop(first)
        val second = daemon()
        notifier.notify(titled("v2", tag = "t"))
        second.awaitNotify().replacesId shouldBe 0
    }

    @Test
    fun `after a restart a reused id does not report as our old notification`() = knownGap("server ids survive a daemon restart") {
        val first = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("old", tag = "old"))
        val id = first.awaitNotify().id
        stop(first)
        val second = daemon(firstId = id)
        // Some other application's notification now holds that number.
        second.click(id)
        events.assertNone()
    }

    @Test
    fun `body escaping follows a restarted server`() = knownGap("capabilities are cached across a daemon restart") {
        val first = daemon(capabilities = listOf("body", "body-markup"))
        val notifier = notifier()
        notifier.notify(titled("t"))
        first.awaitNotify()
        stop(first)
        val second = daemon(capabilities = listOf("body"))
        notifier.notify(Notification(title = "t", body = "a<b"))
        second.awaitNotify().body shouldBe "a<b"
    }

    @Test
    fun `a NUL in the title does not cut off the rest`() = knownGap("C strings end at the first NUL") {
        val server = daemon()
        notifier().notify(titled("before\u0000after"))
        server.awaitNotify().summary shouldContain "after"
    }

    @Test
    fun `a NUL in the body does not cut off the rest`() = knownGap("C strings end at the first NUL") {
        val server = daemon()
        notifier().notify(Notification(title = "t", body = "before\u0000after"))
        server.awaitNotify().body shouldContain "after"
    }

    @Test
    fun `an explicit icon name is not hidden by the default icon bytes`() = knownGap("image-data outranks app_icon on the server") {
        val server = daemon()
        val notifier = notifier(NotifierConfig(appName = "a", defaultIconBytes = pngOf(0xFF000000.toInt())))
        notifier.notify(Notification(title = "t", iconName = "dialog-warning"))
        val got = server.awaitNotify()
        got.appIcon shouldBe "dialog-warning"
        got.image shouldBe null
    }

    @Test
    fun `app id stays off the wire as its KDoc promises`() = knownGap("KDoc says ignored on Linux, the code sends desktop-entry") {
        val server = daemon()
        notifier(NotifierConfig(appName = "a", appId = "Some.Aumid")).notify(titled("t"))
        server.awaitNotify().hints.containsKey("desktop-entry") shouldBe false
    }

    @Test
    fun `a user tag cannot collide with a generated id`() = knownGap("generated ids are predictable") {
        val server = daemon()
        val notifier = notifier()
        val generated = notifier.notify(titled("generated"))!!
        server.awaitNotify()
        notifier.notify(titled("tagged", tag = generated.id))
        server.awaitNotify().replacesId shouldBe 0
    }

    @Test
    fun `generated ids are not reused by another notifier`() = knownGap("each notifier counts from zero") {
        daemon()
        val first = notifier().notify(titled("t"))
        val second = notifier().notify(titled("t"))
        first?.id shouldNotBe second?.id
    }

    @Test
    fun `unsubscribing twice does not remove a second subscription of the same handler`() = knownGap("unsubscribe removes by equality") {
        val server = daemon()
        val notifier = notifier()
        val events = RecordedEvents()
        val handler: (NotificationEvent) -> Unit = { events.add(it) }
        val unsubscribeFirst = notifier.onEvent(handler)
        notifier.onEvent(handler)
        unsubscribeFirst()
        unsubscribeFirst()
        notifier.notify(titled("t", tag = "t"))
        server.click(server.awaitNotify().id)
        events.next(1_000) shouldBe NotificationEvent.Activated("t")
    }
}
