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
        notifier.notify(titled("v1", tag = "reposted")) shouldNotBe null
        before.awaitNotify()
        val old = notifier.notify(titled("left behind", tag = "left-behind"))
        old shouldNotBe null
        before.awaitNotify()

        TestBus.kill()
        Thread.sleep(300)
        notifier.notify(titled("while down")) shouldBe null

        TestBus.ensureRunning()
        val after = daemon()
        notifier.notify(titled("v2", tag = "reposted"))?.id shouldBe "reposted"
        val got = after.awaitNotify()
        got.summary shouldBe "v2"
        // The tag was live on the old bus. A server on the new one starts its ids
        // over, so the old id must not be offered for replacement.
        got.replacesId shouldBe 0

        after.click(got.id)
        events.next() shouldBe NotificationEvent.Activated("reposted")
        // The other notification lived on the old bus and cannot be cancelled any more.
        notifier.cancel(old!!) shouldBe false
        after.assertNoCloseRequest()
        notifier.isOpen shouldBe true
    }
}
