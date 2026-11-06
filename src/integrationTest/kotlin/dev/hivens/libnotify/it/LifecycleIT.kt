package dev.hivens.libnotify.it

import dev.hivens.libnotify.NotificationHandle
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierConfig
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Opening, closing, and calling back into the notifier from its own events. */
internal class LifecycleIT : BusTest() {

    private fun dispatchThreadAlive(): Boolean =
        Thread.getAllStackTraces().keys.any { it.name.startsWith("libnotify-fdo") }

    private fun awaitDispatchThreadGone(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (dispatchThreadAlive() && System.nanoTime() < deadline) Thread.sleep(20)
        dispatchThreadAlive() shouldBe false
    }

    @Test
    fun `a new notifier is open and close closes it`() {
        daemon()
        val notifier = notifier()
        notifier.isOpen shouldBe true
        notifier.close()
        notifier.isOpen shouldBe false
    }

    @Test
    fun `close is idempotent`() {
        daemon()
        val notifier = notifier()
        notifier.close()
        notifier.close()
        notifier.isOpen shouldBe false
    }

    @Test
    fun `notify on a closed notifier returns null and sends nothing`() {
        val server = daemon()
        val notifier = notifier()
        notifier.close()
        notifier.notify(titled("t")) shouldBe null
        server.assertNoNotify()
    }

    @Test
    fun `use closes the notifier`() {
        daemon()
        val notifier = Notifier.create(NotifierConfig(appName = "a"))!!
        notifier.use { it.notify(titled("t")) shouldNotBe null }
        notifier.isOpen shouldBe false
    }

    @Test
    fun `a new notifier works after an old one closed`() {
        val server = daemon()
        notifier().close()
        notifier().notify(titled("t")) shouldNotBe null
        server.awaitNotify()
    }

    @Test
    fun `close stops the dispatch thread`() {
        daemon()
        notifier().close()
        awaitDispatchThreadGone(2_000)
    }

    @Test
    fun `repeated open and close leaks no descriptors or threads`() {
        val server = daemon()
        val descriptors = { File("/proc/self/fd").list()!!.size }
        // The first cycle loads what stays loaded, so it does not count.
        Notifier.create(NotifierConfig(appName = "a"))!!.close()
        val before = descriptors()
        repeat(50) {
            val notifier = Notifier.create(NotifierConfig(appName = "a"))!!
            notifier.notify(titled("t")) shouldNotBe null
            notifier.close()
            server.awaitNotify()
        }
        descriptors() shouldBe before
        dispatchThreadAlive() shouldBe false
    }

    @Test
    fun `a handler can post without waiting on itself`() {
        val server = daemon()
        val notifier = notifier()
        val result = CompletableFuture<NotificationHandle?>()
        val took = AtomicLong()
        notifier.onEvent {
            took.set(elapsedMillis { result.complete(notifier.notify(titled("follow-up", tag = "next"))) })
        }
        notifier.notify(titled("first"))
        server.click(server.awaitNotify().id)
        result.get(10, TimeUnit.SECONDS)?.id shouldBe "next"
        took.get() shouldBeLessThan 1_000L
        server.awaitNotify().summary shouldBe "follow-up"
    }

    @Test
    fun `a handler can cancel without waiting on itself`() {
        val server = daemon()
        val notifier = notifier()
        val result = CompletableFuture<Boolean>()
        val handle = AtomicReference<NotificationHandle>()
        notifier.onEvent { result.complete(notifier.cancel(handle.get())) }
        handle.set(notifier.notify(titled("t")))
        val id = server.awaitNotify().id
        server.click(id)
        result.get(10, TimeUnit.SECONDS) shouldBe true
        server.awaitCloseRequest() shouldBe id
    }

    @Test
    fun `a handler can close the notifier and it shuts down cleanly`() {
        val server = daemon()
        val notifier = notifier()
        val took = CompletableFuture<Long>()
        notifier.onEvent { took.complete(elapsedMillis { notifier.close() }) }
        notifier.notify(titled("t"))
        server.click(server.awaitNotify().id)
        took.get(10, TimeUnit.SECONDS) shouldBeLessThan 1_000L
        notifier.isOpen shouldBe false
        awaitDispatchThreadGone(2_000)
    }

    @Test
    fun `close returns before a slow call does, and the thread cleans up after it`() {
        val server = daemon()
        val notifier = notifier()
        server.mode = ServerMode.Slow(4_000)
        val inFlight = CompletableFuture.supplyAsync { notifier.notify(titled("slow", tag = "slow")) }
        Thread.sleep(200)
        elapsedMillis { notifier.close() } shouldBeLessThan 3_000L
        // A started call keeps its real answer, and only then the connection goes.
        inFlight.get(10, TimeUnit.SECONDS)?.id shouldBe "slow"
        awaitDispatchThreadGone(2_000)
    }

    @Test
    fun `close releases calls still in the queue and they never go out`() {
        val server = daemon()
        val notifier = notifier()
        server.mode = ServerMode.Slow(2_500)
        val inFlight = CompletableFuture.supplyAsync { notifier.notify(titled("first")) }
        Thread.sleep(200)
        val queuedReturned = CountDownLatch(1)
        val queued = CompletableFuture.supplyAsync {
            notifier.notify(titled("queued")).also { queuedReturned.countDown() }
        }
        Thread.sleep(200)
        val closing = CompletableFuture.runAsync { notifier.close() }
        queuedReturned.await(500, TimeUnit.MILLISECONDS) shouldBe true
        queued.get() shouldBe null
        closing.get(10, TimeUnit.SECONDS)
        inFlight.get(10, TimeUnit.SECONDS) shouldNotBe null
        server.awaitNotify().summary shouldBe "first"
        server.assertNoNotify(500)
    }

    @Test
    fun `an interrupted caller keeps its flag and its queued post never goes out`() {
        val server = daemon()
        val notifier = notifier()
        server.mode = ServerMode.Slow(1_000)
        // Holds the dispatch thread, so the interrupted post is certainly still queued.
        val inFlight = CompletableFuture.supplyAsync { notifier.notify(titled("first")) }
        Thread.sleep(200)
        val result = AtomicReference<NotificationHandle?>()
        val flagKept = AtomicBoolean()
        val caller = Thread {
            Thread.currentThread().interrupt()
            result.set(notifier.notify(titled("interrupted")))
            flagKept.set(Thread.currentThread().isInterrupted)
        }
        caller.start()
        caller.join(5_000)
        result.get() shouldBe null
        flagKept.get() shouldBe true
        inFlight.get(10, TimeUnit.SECONDS) shouldNotBe null
        server.awaitNotify().summary shouldBe "first"
        server.assertNoNotify(1_500)
    }
}
