package dev.hivens.libnotify.it

import dev.hivens.libnotify.DismissReason
import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierConfig
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Drives the real Windows or macOS backend once, end to end. Nothing fakes the
 * notification service here, so this only runs on CI: on a developer's machine it
 * would put real banners on their screen.
 *
 * What a CI runner's session can show is unknown, so a null from create or notify
 * is printed rather than failed on. What fails the test is what would hurt a host:
 * an exception escaping the API, a call that does not return in bounded time, or
 * the JVM dying inside native code.
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
        val created = notifier
        println("create: ${created?.capabilities ?: "null, backend unavailable on this runner"}")
        if (created == null) return

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
            var replaced: Any? = null
            elapsedMillis { replaced = it.notify(notification.toBuilder().title("libnotify CI, replaced").build()) } shouldBeLessThan 15_000L
            println("notify again with the same tag: $replaced")
            val handle = it.notify(titled("libnotify CI, to cancel"))
            println("notify untagged: $handle")
            if (handle != null) {
                var cancelled: Boolean? = null
                elapsedMillis { cancelled = it.cancel(handle) } shouldBeLessThan 15_000L
                println("cancel: $cancelled")
                // The library fires this one itself, so it is a contract even
                // where the runner's session shows nothing.
                if (cancelled == true) {
                    events.next() shouldBe NotificationEvent.Dismissed(handle.id, DismissReason.CLOSED)
                }
            }
        }
        created.isOpen shouldBe false
    }
}
