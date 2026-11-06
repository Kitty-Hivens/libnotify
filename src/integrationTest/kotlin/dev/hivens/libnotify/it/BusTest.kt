package dev.hivens.libnotify.it

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierConfig
import io.kotest.assertions.fail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Starts the private bus first and closes whatever a test opened. */
@EnabledOnOs(OS.LINUX)
internal abstract class BusTest {
    private val daemons = ArrayList<FakeDaemon>()
    private val notifiers = ArrayList<Notifier>()

    @BeforeEach
    fun requirePrivateBus() {
        TestBus.address
    }

    @AfterEach
    fun closeEverything() {
        notifiers.forEach { it.close() }
        daemons.forEach { it.close() }
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
 * The contract [body] states is not met yet. The test passes while the assertion
 * fails, and fails as soon as it stops failing, so a fix cannot leave the entry
 * behind. Anything but a failed assertion is a real failure.
 */
internal fun knownGap(reason: String, body: () -> Unit) {
    try {
        body()
    } catch (expected: AssertionError) {
        System.err.println("known gap: $reason (${expected.message})")
        return
    }
    fail("This gap is closed, turn it into a plain test: $reason")
}
