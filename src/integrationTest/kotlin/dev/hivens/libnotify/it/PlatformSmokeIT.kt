package dev.hivens.libnotify.it

import dev.hivens.libnotify.DismissReason
import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierConfig
import io.kotest.assertions.fail
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Drives the real Windows or macOS backend once, end to end. Nothing fakes the
 * notification service here, so this only runs on CI: on a developer's machine it
 * would put real banners on their screen.
 *
 * Hosted runners accept every one of these calls, so a null or false is a
 * regression. What this cannot show is whether a banner was visible, or what a
 * click does: nobody watches or clicks on a runner.
 */
@EnabledOnOs(OS.WINDOWS, OS.MAC)
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
internal class PlatformSmokeIT {

    @Test
    fun `the native backend survives a full round trip`() {
        var notifier: Notifier? = null
        elapsedMillis {
            notifier = Notifier.create(NotifierConfig(appName = "libnotify CI", appId = "dev.hivens.libnotify.ci"))
        } shouldBeLessThan 15_000L
        val created = notifier ?: fail("Notifier.create returned null on a runner where it has worked")
        println("create: ${created.capabilities}")

        val events = RecordedEvents()
        created.onEvent { events.add(it) }
        created.use {
            val notification = Notification(
                title = "libnotify CI",
                body = "a <b>body</b> & text",
                iconBytes = pngOf(0xFFBB86FC.toInt(), 0xFF000000.toInt()),
                actions = listOf(NotificationAction("one", "One"), NotificationAction("two", "Two")),
                tag = "ci-smoke",
            )
            var first: Any? = null
            elapsedMillis { first = it.notify(notification) } shouldBeLessThan 15_000L
            println("notify: $first")
            first shouldNotBe null

            var replaced: Any? = null
            elapsedMillis { replaced = it.notify(notification.toBuilder().title("libnotify CI, replaced").build()) } shouldBeLessThan 15_000L
            println("notify again with the same tag: $replaced")
            replaced shouldNotBe null

            val handle = it.notify(titled("libnotify CI, to cancel")) ?: fail("an untagged post returned null")
            println("notify untagged: $handle")
            var cancelled: Boolean? = null
            elapsedMillis { cancelled = it.cancel(handle) } shouldBeLessThan 15_000L
            println("cancel: $cancelled")
            cancelled shouldBe true

            // The library fires this one itself, so it is a contract even where the
            // runner shows nothing. The platform may report other dismissals around
            // it, the replaced toast for one, so look for it rather than take the first.
            awaitEvent(events, NotificationEvent.Dismissed(handle.id, DismissReason.CLOSED))
        }
        created.isOpen shouldBe false
    }

    private fun awaitEvent(events: RecordedEvents, expected: NotificationEvent) {
        val seen = ArrayList<NotificationEvent>()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val event = runCatching { events.next(100) }.getOrNull() ?: continue
            if (event == expected) {
                println("events before it: $seen")
                return
            }
            seen.add(event)
        }
        fail("no $expected within 5 s, saw $seen")
    }
}
