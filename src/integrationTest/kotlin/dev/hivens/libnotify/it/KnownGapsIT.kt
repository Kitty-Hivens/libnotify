package dev.hivens.libnotify.it

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotifierConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The contract as it should be, for behaviour that does not meet it yet. See
 * [knownGap]: each entry turns red when its gap closes, and then moves next to the
 * tests for that area as a plain test. Everything outside `gap { }` is an
 * ordinary assertion, so a broken setup fails here instead of passing as a gap.
 */
internal class KnownGapsIT : BusTest() {

    @Test
    fun `a NUL in the title does not cut off the rest`() = knownGap("C strings end at the first NUL") {
        val server = daemon()
        notifier().notify(titled("before\u0000after")) shouldNotBe null
        val got = server.awaitNotify()
        gap { got.summary shouldContain "after" }
    }

    @Test
    fun `a NUL in the body does not cut off the rest`() = knownGap("C strings end at the first NUL") {
        val server = daemon()
        notifier().notify(Notification(title = "t", body = "before\u0000after")) shouldNotBe null
        val got = server.awaitNotify()
        gap { got.body shouldContain "after" }
    }

    @Test
    fun `an explicit icon name is not hidden by the default icon bytes`() = knownGap("image-data outranks app_icon on the server") {
        val server = daemon()
        val notifier = notifier(NotifierConfig(appName = "a", defaultIconBytes = pngOf(0xFF000000.toInt())))
        notifier.notify(Notification(title = "t", iconName = "dialog-warning")) shouldNotBe null
        val got = server.awaitNotify()
        got.appIcon shouldBe "dialog-warning"
        gap { got.image shouldBe null }
    }
}
