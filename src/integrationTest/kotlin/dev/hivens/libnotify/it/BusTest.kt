package dev.hivens.libnotify.it

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierConfig
import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.OS
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Starts the private bus first and closes whatever a test opened. Skipped off
 * Linux by an assumption rather than a class annotation, which a subclass would
 * not be guaranteed to inherit.
 */
internal abstract class BusTest {
    private val daemons = ArrayList<FakeDaemon>()
    private val notifiers = ArrayList<Notifier>()

    @BeforeEach
    fun requirePrivateBus() {
        assumeTrue(OS.current() == OS.LINUX, "the freedesktop backend only runs on Linux")
        TestBus.address
    }

    /** Closing a daemon also fails the test if its thread died or the library broke the protocol. */
    @AfterEach
    fun closeEverything() {
        notifiers.forEach { it.close() }
        val failures = daemons.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    protected fun daemon(
        capabilities: List<String> = FakeDaemon.FULL_CAPABILITIES,
        firstId: Int = 1,
    ): FakeDaemon = FakeDaemon(capabilities, firstId).also { daemons.add(it) }

    protected fun notifier(config: NotifierConfig = NotifierConfig(appName = "it-app")): Notifier =
        (Notifier.create(config) ?: fail("Notifier.create returned null on a working bus"))
            .also { notifiers.add(it) }

    /** Stop a daemon mid-test, the way one going away looks from the bus. */
    protected fun stop(daemon: FakeDaemon) {
        daemons.remove(daemon)
        daemon.close()
    }

    /**
     * Proves that no event is pending before [marker] by making the server raise
     * one more event and checking it is the next to arrive. The server sends
     * everything on one connection, so this needs no timing window. Only valid
     * for events the same server would have raised.
     */
    protected fun RecordedEvents.assertNothingBefore(server: FakeDaemon, markerId: Int, expected: NotificationEvent) {
        server.click(markerId)
        next() shouldBe expected
    }

    protected fun Notifier.record(): RecordedEvents = RecordedEvents().also { recorded ->
        onEvent { recorded.add(it) }
    }
}

internal class RecordedEvents {
    private val events = LinkedBlockingQueue<NotificationEvent>()

    fun add(event: NotificationEvent) {
        events.add(event)
    }

    fun next(timeoutMs: Long = 5_000): NotificationEvent =
        events.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: fail("no event within $timeoutMs ms")

    /** Fails if an event arrives within [windowMs]. Must outlast the library's signal poll interval. */
    fun assertNone(windowMs: Long = 400) {
        events.poll(windowMs, TimeUnit.MILLISECONDS)?.let { fail("unexpected event: $it") }
    }
}

internal fun titled(title: String, tag: String? = null): Notification = Notification(title = title, tag = tag)

internal fun elapsedMillis(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000
}

/** PNG bytes of one row of ARGB pixels. */
internal fun pngOf(vararg argb: Int): ByteArray {
    val image = BufferedImage(argb.size, 1, BufferedImage.TYPE_INT_ARGB)
    argb.forEachIndexed { x, pixel -> image.setRGB(x, 0, pixel) }
    return ByteArrayOutputStream().also { ImageIO.write(image, "PNG", it) }.toByteArray()
}

/**
 * A contract the library does not meet yet. [body] sets the scene with ordinary
 * assertions and states the unmet contract inside exactly one [GapScope.gap]
 * block. The test passes while that block fails, and fails once it passes, so a
 * fix cannot leave the entry behind.
 *
 * Only a failure inside [GapScope.gap] counts as the gap. A failure anywhere
 * else, a setup that did not work or a notification that never arrived, fails
 * the test for real, so a broken backend cannot pass as a known gap.
 */
internal fun knownGap(reason: String, body: GapScope.() -> Unit) {
    val scope = GapScope()
    try {
        scope.body()
    } catch (open: GapScope.StillOpen) {
        System.err.println("known gap: $reason (${open.cause?.message})")
        return
    }
    if (!scope.checked) fail("knownGap '$reason' never reached its gap { } block")
    fail("This gap is closed, turn it into a plain test: $reason")
}

internal class GapScope {
    internal class StillOpen(cause: AssertionError) : RuntimeException(cause)

    internal var checked = false
        private set

    /** The contract that is not met yet. Must be the last thing [knownGap]'s body does. */
    fun gap(assertion: () -> Unit) {
        check(!checked) { "a knownGap has exactly one gap { } block" }
        checked = true
        try {
            assertion()
        } catch (failed: AssertionError) {
            throw StillOpen(failed)
        }
    }
}
