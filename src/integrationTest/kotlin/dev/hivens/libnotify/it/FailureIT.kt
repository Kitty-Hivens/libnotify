package dev.hivens.libnotify.it

import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierConfig
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A missing or misbehaving server turns into "no banner", within a known time. */
internal class FailureIT : BusTest() {

    @Test
    fun `create works with no server running and reports the spec baseline`() {
        val notifier = notifier()
        notifier.isOpen shouldBe true
        notifier.capabilities.replace shouldBe true
        notifier.capabilities.closeEvents shouldBe true
    }

    @Test
    fun `notify returns null quickly when no server is running`() {
        val notifier = notifier()
        elapsedMillis { notifier.notify(titled("t")) shouldBe null } shouldBeLessThan 1_000L
    }

    @Test
    fun `a server that starts later is picked up`() {
        val notifier = notifier()
        notifier.notify(titled("too early")) shouldBe null
        val server = daemon()
        notifier.notify(titled("in time")) shouldNotBe null
        server.awaitNotify().summary shouldBe "in time"
    }

    @Test
    fun `a server going away turns posts into null, and a new one is picked up`() {
        val first = daemon()
        val notifier = notifier()
        notifier.notify(titled("t")) shouldNotBe null
        first.awaitNotify()
        stop(first)
        notifier.notify(titled("nobody home")) shouldBe null
        val second = daemon()
        notifier.notify(titled("back")) shouldNotBe null
        second.awaitNotify().summary shouldBe "back"
    }

    @Test
    fun `an error from the server turns into null`() {
        val server = daemon()
        val notifier = notifier()
        server.mode = ServerMode.Failing
        notifier.notify(titled("t")) shouldBe null
    }

    @Test
    fun `a server that never answers turns into null within the reply timeout`() {
        val server = daemon()
        val notifier = notifier()
        server.mode = ServerMode.Silent
        elapsedMillis { notifier.notify(titled("t")) shouldBe null } shouldBeLessThan 7_000L
    }

    @Test
    fun `a hung server blocks create for a bounded time and the notifier still comes up`() {
        val server = daemon()
        server.mode = ServerMode.Silent
        var created: Notifier? = null
        elapsedMillis { created = Notifier.create(NotifierConfig(appName = "a")) } shouldBeLessThan 3_000L
        created.shouldNotBe(null)
        created?.close()
    }

    @Test
    fun `the worst case wait stays bounded with capabilities still unknown`() {
        val notifier = notifier()
        val server = daemon()
        server.mode = ServerMode.Silent
        elapsedMillis { notifier.notify(titled("t")) shouldBe null } shouldBeLessThan 9_000L
    }

    @Test
    fun `a post behind a hung call fails fast and never goes out`() {
        val server = daemon()
        val notifier = notifier()
        server.mode = ServerMode.Slow(2_500)
        val first = CompletableFuture.supplyAsync { notifier.notify(titled("first")) }
        Thread.sleep(200)
        elapsedMillis { notifier.notify(titled("behind")) shouldBe null } shouldBeLessThan 1_500L
        first.get(10, TimeUnit.SECONDS) shouldNotBe null
        server.awaitNotify().summary shouldBe "first"
        server.assertNoNotify(3_000)
    }

    @Test
    fun `a slow server that answers within the timeout is a success`() {
        val server = daemon()
        val notifier = notifier()
        server.mode = ServerMode.Slow(1_500)
        notifier.notify(titled("t")) shouldNotBe null
        server.awaitNotify().summary shouldBe "t"
    }
}
