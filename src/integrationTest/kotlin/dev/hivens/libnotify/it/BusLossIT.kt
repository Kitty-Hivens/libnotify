package dev.hivens.libnotify.it

import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** Kills the bus, so it relies on the task giving every class its own worker. */
internal class BusLossIT : BusTest() {

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
        elapsedMillis { notifier.close() } shouldBeLessThan 1_000L
        notifier.isOpen shouldBe false
    }
}
