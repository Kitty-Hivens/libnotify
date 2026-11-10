package dev.hivens.libnotify.it

import dev.hivens.libnotify.Notification
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `Notification.body` is plain text whatever the server does with markup. */
internal class BodyMarkupIT : BusTest() {
    private val text = "AT&T <b>x</b> report<final>.pdf"
    private val escaped = "AT&amp;T &lt;b&gt;x&lt;/b&gt; report&lt;final&gt;.pdf"

    @Test
    fun `a server that parses markup gets the body escaped`() {
        val server = daemon(capabilities = listOf("body", "body-markup"))
        notifier().notify(Notification(title = "t", body = text))
        server.awaitNotify().body shouldBe escaped
    }

    @Test
    fun `a server without markup gets the body untouched`() {
        val server = daemon(capabilities = listOf("body"))
        notifier().notify(Notification(title = "t", body = text))
        server.awaitNotify().body shouldBe text
    }

    @Test
    fun `the title is never escaped, the spec gives it no markup`() {
        val server = daemon(capabilities = listOf("body", "body-markup"))
        notifier().notify(Notification(title = text))
        server.awaitNotify().summary shouldBe text
    }

    @Test
    fun `a server that starts after create is still asked about markup`() {
        val notifier = notifier()
        notifier.capabilities.bodyMarkup shouldBe false
        val server = daemon(capabilities = listOf("body", "body-markup"))
        notifier.notify(Notification(title = "t", body = text))
        server.awaitNotify().body shouldBe escaped
    }

    @Test
    fun `a server without markup that starts after create gets the body untouched`() {
        val notifier = notifier()
        val server = daemon(capabilities = listOf("body"))
        notifier.notify(Notification(title = "t", body = text))
        server.awaitNotify().body shouldBe text
    }

    @Test
    fun `capabilities report the markup stance of the server at create time`() {
        daemon(capabilities = listOf("body", "body-markup"))
        notifier().capabilities.bodyMarkup shouldBe true
    }
}
