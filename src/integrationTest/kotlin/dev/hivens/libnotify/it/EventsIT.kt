package dev.hivens.libnotify.it

import dev.hivens.libnotify.DismissReason
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.NotificationListener
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What signals from the server turn into. */
internal class EventsIT : BusTest() {

    @Test
    fun `a body click arrives as Activated with the handle id`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        val handle = notifier.notify(titled("t", tag = "mine"))
        server.click(server.awaitNotify().id)
        events.next() shouldBe NotificationEvent.Activated(handle!!.id)
    }

    @Test
    fun `a button click arrives as ActionInvoked with the action id`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        val handle = notifier.notify(
            Notification(title = "t", actions = listOf(NotificationAction("reply", "Reply"))),
        )
        server.click(server.awaitNotify().id, "reply")
        events.next() shouldBe NotificationEvent.ActionInvoked(handle!!.id, "reply")
    }

    @Test
    fun `close reasons map onto DismissReason`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        val reasons = listOf(
            1 to DismissReason.EXPIRED,
            2 to DismissReason.DISMISSED_BY_USER,
            3 to DismissReason.CLOSED,
            4 to DismissReason.UNKNOWN,
            99 to DismissReason.UNKNOWN,
        )
        for ((code, reason) in reasons) {
            val handle = notifier.notify(titled("t"))
            server.closed(server.awaitNotify().id, code)
            events.next() shouldBe NotificationEvent.Dismissed(handle!!.id, reason)
        }
    }

    @Test
    fun `each notification reports under its own id`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        val first = notifier.notify(titled("one", tag = "one"))
        val firstId = server.awaitNotify().id
        val second = notifier.notify(titled("two", tag = "two"))
        val secondId = server.awaitNotify().id
        server.click(secondId)
        events.next() shouldBe NotificationEvent.Activated(second!!.id)
        server.click(firstId)
        events.next() shouldBe NotificationEvent.Activated(first!!.id)
    }

    @Test
    fun `a replaced banner keeps reporting under the same id`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("v1", tag = "t"))
        server.awaitNotify()
        notifier.notify(titled("v2", tag = "t"))
        server.click(server.awaitNotify().id)
        events.next() shouldBe NotificationEvent.Activated("t")
    }

    @Test
    fun `signals for a notification that is not ours are ignored`() {
        val server = daemon(firstId = 100)
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("t", tag = "t"))
        val ours = server.awaitNotify().id
        server.click(55)
        server.closed(55, 1)
        events.assertNothingBefore(server, ours, NotificationEvent.Activated("t"))
    }

    @Test
    fun `a closed notification stops reporting clicks`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("t", tag = "t"))
        val id = server.awaitNotify().id
        server.closed(id, 1)
        events.next() shouldBe NotificationEvent.Dismissed("t", DismissReason.EXPIRED)
        notifier.notify(titled("marker", tag = "marker"))
        val marker = server.awaitNotify().id
        server.click(id)
        events.assertNothingBefore(server, marker, NotificationEvent.Activated("marker"))
    }

    @Test
    fun `every handler gets the event`() {
        val server = daemon()
        val notifier = notifier()
        val first = notifier.record()
        val second = notifier.record()
        notifier.notify(titled("t", tag = "t"))
        server.click(server.awaitNotify().id)
        first.next() shouldBe NotificationEvent.Activated("t")
        second.next() shouldBe NotificationEvent.Activated("t")
    }

    @Test
    fun `a handler that throws does not stop the next one`() {
        val server = daemon()
        val notifier = notifier()
        notifier.onEvent { error("handler failure on purpose") }
        val events = notifier.record()
        notifier.notify(titled("t", tag = "t"))
        val id = server.awaitNotify().id
        server.click(id)
        events.next() shouldBe NotificationEvent.Activated("t")
        server.click(id)
        events.next() shouldBe NotificationEvent.Activated("t")
    }

    @Test
    fun `closing a subscription stops delivery, and closing it twice is harmless`() {
        val server = daemon()
        val notifier = notifier()
        val gone = RecordedEvents()
        val subscription = notifier.onEvent { gone.add(it) }
        val kept = notifier.record()
        notifier.notify(titled("t", tag = "t"))
        val id = server.awaitNotify().id
        subscription.close()
        subscription.close()
        server.click(id)
        kept.next() shouldBe NotificationEvent.Activated("t")
        gone.assertNone()
    }

    @Test
    fun `closing one of two subscriptions of the same listener keeps the other`() {
        val server = daemon()
        val notifier = notifier()
        val events = RecordedEvents()
        val listener = NotificationListener { events.add(it) }
        val first = notifier.onEvent(listener)
        notifier.onEvent(listener)
        first.close()
        first.close()
        notifier.notify(titled("t", tag = "t"))
        val id = server.awaitNotify().id
        notifier.notify(titled("marker", tag = "marker"))
        val marker = server.awaitNotify().id
        server.click(id)
        events.next() shouldBe NotificationEvent.Activated("t")
        // A registration that survived twice would have queued a second "t" here.
        events.assertNothingBefore(server, marker, NotificationEvent.Activated("marker"))
    }

    @Test
    fun `no event after close`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("t"))
        val id = server.awaitNotify().id
        notifier.close()
        server.click(id)
        events.assertNone()
    }

    @Test
    fun `two notifiers each hear only their own notifications`() {
        val server = daemon()
        val first = notifier()
        val second = notifier()
        val firstEvents = first.record()
        val secondEvents = second.record()
        first.notify(titled("one", tag = "one"))
        val firstId = server.awaitNotify().id
        second.notify(titled("two", tag = "two"))
        val secondId = server.awaitNotify().id
        server.click(firstId)
        firstEvents.next() shouldBe NotificationEvent.Activated("one")
        // Each notifier's events arrive in the server's order, so the next event
        // either side sees settles whether a stray one came first.
        server.click(secondId)
        secondEvents.next() shouldBe NotificationEvent.Activated("two")
        server.click(firstId)
        firstEvents.next() shouldBe NotificationEvent.Activated("one")
    }

    @Test
    fun `a slow listener holds back other threads, whose calls give up unsent after a second`() {
        val server = daemon()
        val notifier = notifier()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        notifier.onEvent {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        notifier.notify(titled("t")) shouldNotBe null
        server.click(server.awaitNotify().id)
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        try {
            elapsedMillis { notifier.notify(titled("behind the listener")) shouldBe null } shouldBeLessThan 2_000L
        } finally {
            release.countDown()
        }
        server.assertNoNotify()
        notifier.notify(titled("after the listener"))?.id?.isNotEmpty() shouldBe true
        server.awaitNotify().summary shouldBe "after the listener"
    }
}
