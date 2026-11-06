package dev.hivens.libnotify

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DispatchCallsTest {

    private val calls = DispatchCalls(LoggerFactory.getLogger("DispatchCallsTest"))
    private val pumping = AtomicBoolean(true)
    private var pump: Thread? = null

    private fun startPump() {
        val thread = Thread {
            while (pumping.get()) calls.runQueued(10)
        }
        calls.ownedBy(thread)
        pump = thread
        thread.start()
    }

    @AfterEach
    fun stopPump() {
        pumping.set(false)
        pump?.join(5_000)
    }

    @Test
    fun `a call runs on the owner and returns its value`() {
        startPump()
        val ranOn = calls.call("t", null, 5_000, 5_000) { Thread.currentThread() }
        ranOn shouldBe pump
    }

    @Test
    fun `a call from the owner runs in place instead of waiting on itself`() {
        calls.ownedBy(Thread.currentThread())
        // Nothing pumps the queue here, so queueing would run into the wait.
        calls.call("t", "failed", 1, 1) { "done" } shouldBe "done"
    }

    @Test
    fun `a call still queued when the wait runs out never runs`() {
        val ran = AtomicBoolean()
        calls.call("t", "failed", 20, 5_000) { ran.set(true); "done" } shouldBe "failed"
        calls.runQueued(0)
        ran.get() shouldBe false
    }

    @Test
    fun `a call the owner already started is waited for past the queue wait`() {
        startPump()
        val result = calls.call("t", "failed", 20, 5_000) {
            Thread.sleep(200)
            "done"
        }
        result shouldBe "done"
    }

    @Test
    fun `the work cap stops the wait for a call that never returns`() {
        startPump()
        val release = CountDownLatch(1)
        try {
            calls.call("t", "failed", 5_000, 50) { release.await(); "done" } shouldBe "failed"
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `shutdown releases a waiting caller at once and the work never runs`() {
        val ran = AtomicBoolean()
        val answer = CompletableFuture.supplyAsync {
            calls.call("t", "failed", 60_000, 60_000) { ran.set(true); "done" }
        }
        // The caller has to reach the queue first, or shutdown only proves refusal.
        Thread.sleep(100)
        calls.shutdown()
        answer.get(5, TimeUnit.SECONDS) shouldBe "failed"
        calls.runQueued(0)
        ran.get() shouldBe false
    }

    @Test
    fun `a call after shutdown fails without running`() {
        calls.shutdown()
        val ran = AtomicBoolean()
        calls.call("t", "failed", 60_000, 60_000) { ran.set(true); "done" } shouldBe "failed"
        calls.runQueued(0)
        ran.get() shouldBe false
    }

    @Test
    fun `an interrupted caller keeps its flag and queued work never runs`() {
        val ran = AtomicBoolean()
        Thread.currentThread().interrupt()
        val result = calls.call("t", "failed", 60_000, 60_000) { ran.set(true); "done" }
        // Thread.interrupted() also clears the flag so it cannot leak into other tests.
        Thread.interrupted() shouldBe true
        result shouldBe "failed"
        calls.runQueued(0)
        ran.get() shouldBe false
    }

    @Test
    fun `work that throws reports the failed value`() {
        startPump()
        calls.call<String>("t", "failed", 5_000, 5_000) { error("boom") } shouldBe "failed"
    }

    @Test
    fun `work that throws on the owner reports the failed value too`() {
        calls.ownedBy(Thread.currentThread())
        calls.call<String>("t", "failed", 1, 1) { error("boom") } shouldBe "failed"
    }

    @Test
    fun `posted work runs on the owner and is dropped after shutdown`() {
        val ran = AtomicBoolean()
        calls.post("t") { ran.set(true) }
        calls.runQueued(0)
        ran.get() shouldBe true

        ran.set(false)
        calls.shutdown()
        calls.post("t") { ran.set(true) }
        calls.runQueued(0)
        ran.get() shouldBe false
    }
}
