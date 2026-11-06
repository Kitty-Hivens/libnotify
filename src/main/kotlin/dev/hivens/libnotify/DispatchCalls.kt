package dev.hivens.libnotify

import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands work from any caller to the one thread a backend owns, and keeps the
 * caller's answer honest about what happened to it.
 *
 * The failed value a caller gets back means the work did not go out, with one
 * exception: work the owner has already started cannot be recalled, so a caller
 * that stops waiting for it (interrupted, or past the cap) may still see it take
 * effect. Work still in the queue is claimed atomically, so a caller that gave up
 * on it can rely on it never running.
 */
internal class DispatchCalls(private val log: Logger) {

    private val queue = LinkedBlockingQueue<Entry<*>>()

    /** Guarded by [queue], so nothing can slip in behind the drain in [shutdown]. */
    private var shut = false

    @Volatile private var owner: Thread? = null

    /** Name the thread that runs [runQueued]. Set it before that thread starts. */
    fun ownedBy(thread: Thread) {
        owner = thread
    }

    /**
     * Run [work] on the owner and wait for the result.
     *
     * @param queueWaitMs how long the work may wait for the owner to pick it up.
     * @param workCapMs how long to wait once it started. A safety net: the work
     *   is expected to be bounded by its own native timeouts.
     */
    fun <T> call(what: String, failed: T, queueWaitMs: Long, workCapMs: Long, work: () -> T): T {
        val entry = Entry(what, failed, work)
        // An event handler runs on the owner. Queueing from there would wait on
        // the only thread that can answer.
        if (Thread.currentThread() === owner) {
            entry.run()
            return entry.result.join()
        }
        if (!enqueue(entry)) return failed
        return await(entry, queueWaitMs, workCapMs)
    }

    /** Queue [work] with nobody waiting for it. Dropped once [shutdown] has run. */
    fun post(what: String, work: () -> Unit) {
        enqueue(Entry(what, Unit, work))
    }

    /** Owner side: wait up to [waitMs] for work, then run everything queued. */
    fun runQueued(waitMs: Long) {
        var entry = queue.poll(waitMs, TimeUnit.MILLISECONDS)
        while (entry != null) {
            entry.run()
            entry = queue.poll()
        }
    }

    /** Refuse new work and fail whatever is still queued, releasing its callers now. */
    fun shutdown() {
        synchronized(queue) {
            shut = true
            generateSequence { queue.poll() }.forEach { it.abandon() }
        }
    }

    private fun enqueue(entry: Entry<*>): Boolean = synchronized(queue) {
        if (shut) false else queue.add(entry)
    }

    private fun <T> await(entry: Entry<T>, queueWaitMs: Long, workCapMs: Long): T {
        try {
            try {
                return entry.result.get(queueWaitMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                if (entry.claim()) {
                    log.warn("{} not started within {} ms, dropped without running", entry.what, queueWaitMs)
                    return entry.failed
                }
            }
            return entry.result.get(workCapMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            log.warn("{} still running after {} ms, no longer waiting for it", entry.what, workCapMs)
            return entry.failed
        } catch (e: InterruptedException) {
            entry.claim()
            Thread.currentThread().interrupt()
            return entry.failed
        }
    }

    private inner class Entry<T>(val what: String, val failed: T, private val work: () -> T) {
        val result = CompletableFuture<T>()
        private val claimed = AtomicBoolean()

        /** Whoever wins this decides the work's fate: the owner runs it, a caller drops it. */
        fun claim(): Boolean = claimed.compareAndSet(false, true)

        fun run() {
            if (!claim()) return
            val value = try {
                work()
            } catch (t: Throwable) {
                log.warn("{} threw", what, t)
                failed
            }
            result.complete(value)
        }

        fun abandon() {
            if (claim()) result.complete(failed)
        }
    }
}
