package dev.hivens.libnotify.it

import dev.hivens.libnotify.DismissReason
import dev.hivens.libnotify.NotificationEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

internal class CancelIT : BusTest() {

    @Test
    fun `cancel asks the server to close that notification`() {
        val server = daemon()
        val notifier = notifier()
        val handle = notifier.notify(titled("t"))!!
        val id = server.awaitNotify().id
        notifier.cancel(handle) shouldBe true
        server.awaitCloseRequest() shouldBe id
    }

    @Test
    fun `cancel reports back as exactly one Dismissed with reason CLOSED`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        val handle = notifier.notify(titled("t"))!!
        server.awaitNotify()
        notifier.cancel(handle) shouldBe true
        // Already delivered by the time cancel returned: no waiting allowed.
        events.next(0) shouldBe NotificationEvent.Dismissed(handle.id, DismissReason.CLOSED)
        notifier.notify(titled("marker", tag = "marker"))
        val marker = server.awaitNotify().id
        events.assertNothingBefore(server, marker, NotificationEvent.Activated("marker"))
    }

    @Test
    fun `cancelling a closed banner returns false and asks nothing`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        val handle = notifier.notify(titled("t"))!!
        server.closed(server.awaitNotify().id, 2)
        events.next() shouldBe NotificationEvent.Dismissed(handle.id, DismissReason.DISMISSED_BY_USER)
        notifier.cancel(handle) shouldBe false
        server.assertNoCloseRequest()
    }

    @Test
    fun `a notifier that never posted an id does not know its handle`() {
        val server = daemon()
        val owner = notifier()
        val other = notifier()
        val handle = owner.notify(titled("t", tag = "t"))!!
        server.awaitNotify()
        other.cancel(handle) shouldBe false
        server.assertNoCloseRequest()
    }

    @Test
    fun `cancel on a closed notifier returns false`() {
        daemon()
        val notifier = notifier()
        val handle = notifier.notify(titled("t"))!!
        notifier.close()
        notifier.cancel(handle) shouldBe false
    }

    @Test
    fun `cancel returns false when the server answers with an error`() {
        val server = daemon()
        val notifier = notifier()
        val handle = notifier.notify(titled("t"))!!
        server.awaitNotify()
        server.mode = ServerMode.Failing
        notifier.cancel(handle) shouldBe false
    }

    @Test
    fun `cancel returns false when the server never answers`() {
        val server = daemon()
        val notifier = notifier()
        val handle = notifier.notify(titled("t"))!!
        server.awaitNotify()
        server.mode = ServerMode.Silent
        notifier.cancel(handle) shouldBe false
    }

    @Test
    fun `cancel returns false when the server went away`() {
        val server = daemon()
        val notifier = notifier()
        val handle = notifier.notify(titled("t"))!!
        server.awaitNotify()
        stop(server)
        notifier.cancel(handle) shouldBe false
    }
}
