package dev.hivens.libnotify.it

import dev.hivens.libnotify.NotificationEvent
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Kills the bus, so it relies on the task giving every class its own worker. */
internal class BusLossIT : BusTest() {

    @BeforeEach
    fun busUp() {
        TestBus.ensureRunning()
    }

    @Test
    fun `losing the session bus does not take the host down and calls fail fast`() {
        val server = daemon()
        val notifier = notifier()
        val handle = notifier.notify(titled("t"))
        handle shouldNotBe null
        server.awaitNotify()

        TestBus.kill()
        Thread.sleep(300)

        elapsedMillis { notifier.notify(titled("after")) shouldBe null } shouldBeLessThan 1_000L
        notifier.cancel(handle!!) shouldBe false
        notifier.isOpen shouldBe true
        elapsedMillis { notifier.close() } shouldBeLessThan 1_000L
        notifier.isOpen shouldBe false
    }

    @Test
    fun `the notifier reconnects by itself once the session bus is back`() {
        val before = daemon()
        val notifier = notifier()
        val events = notifier.record()
        val old = notifier.notify(titled("before", tag = "before"))
        old shouldNotBe null
        before.awaitNotify()

        TestBus.kill()
        Thread.sleep(300)
        notifier.notify(titled("while down")) shouldBe null

        TestBus.ensureRunning()
        val after = daemon()
        notifier.notify(titled("back", tag = "back"))?.id shouldBe "back"
        val got = after.awaitNotify()
        got.summary shouldBe "back"
        // A server on the new bus starts its ids over, so nothing old may be asked to be replaced.
        got.replacesId shouldBe 0

        after.click(got.id)
        events.next() shouldBe NotificationEvent.Activated("back")
        // The old notification lived on the old bus and cannot be cancelled any more.
        notifier.cancel(old!!) shouldBe false
        notifier.isOpen shouldBe true
    }
}
