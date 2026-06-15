package dev.hivens.libnotify.windows

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.Timeout
import dev.hivens.libnotify.Urgency
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The toast XML builder is pure string assembly -- the Windows logic most
 * prone to silent escaping/structure bugs and the part that needs no Windows
 * to test.
 */
class ToastXmlTest {

    @Test
    fun `basic toast carries title and body`() {
        val xml = ToastXml.build(Notification(title = "Hi", body = "There"), imageUri = null)
        xml shouldContain "<text>Hi</text>"
        xml shouldContain "<text>There</text>"
        xml shouldContain "template=\"ToastGeneric\""
        // launch arguments route a body click to Activated.
        xml shouldContain "launch=\"default\""
    }

    @Test
    fun `xml special characters are escaped in text and attributes`() {
        val xml = ToastXml.build(
            Notification(
                title = "a & b < c > d \" e",
                actions = listOf(NotificationAction(id = "id\"&<", label = "L<&>")),
            ),
            imageUri = null,
        )
        xml shouldContain "a &amp; b &lt; c &gt; d &quot; e"
        xml shouldContain "content=\"L&lt;&amp;&gt;\""
        xml shouldContain "arguments=\"id&quot;&amp;&lt;\""
        // A raw unescaped ampersand would break the XML parse.
        xml shouldNotContain "a & b"
    }

    @Test
    fun `actions render as buttons`() {
        val xml = ToastXml.build(
            Notification(
                title = "t",
                actions = listOf(
                    NotificationAction(id = "ok", label = "OK"),
                    NotificationAction(id = "no", label = "Dismiss"),
                ),
            ),
            imageUri = null,
        )
        xml shouldContain "<actions>"
        xml shouldContain "content=\"OK\" arguments=\"ok\""
        xml shouldContain "content=\"Dismiss\" arguments=\"no\""
    }

    @Test
    fun `actions are capped at the toast maximum`() {
        val many = (1..8).map { NotificationAction(id = "a$it", label = "A$it") }
        val xml = ToastXml.build(Notification(title = "t", actions = many), imageUri = null)
        // Only the first five survive; the sixth must not appear.
        xml shouldContain "arguments=\"a5\""
        xml shouldNotContain "arguments=\"a6\""
    }

    @Test
    fun `image placement is emitted when a uri is supplied`() {
        val xml = ToastXml.build(Notification(title = "t"), imageUri = "file:///C:/tmp/icon.png")
        xml shouldContain "placement=\"appLogoOverride\""
        xml shouldContain "src=\"file:///C:/tmp/icon.png\""
    }

    @Test
    fun `critical urgency selects the urgent scenario`() {
        ToastXml.scenarioFor(Urgency.CRITICAL) shouldBe "urgent"
        ToastXml.scenarioFor(Urgency.NORMAL) shouldBe null
        ToastXml.scenarioFor(Urgency.LOW) shouldBe null
    }

    @Test
    fun `duration quantises to short or long`() {
        ToastXml.durationFor(Timeout.ServerDefault) shouldBe null
        ToastXml.durationFor(Timeout.Never) shouldBe "long"
        ToastXml.durationFor(Timeout.After(3_000)) shouldBe "short"
        ToastXml.durationFor(Timeout.After(40_000)) shouldBe "long"
    }
}
