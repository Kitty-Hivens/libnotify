package dev.hivens.libnotify.windows

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.Timeout
import dev.hivens.libnotify.Urgency
import dev.hivens.libnotify.knownGap
import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

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

    /** The shell rejects a payload that is not well-formed XML, and the whole toast with it. */
    private fun parse(xml: String): Document {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(e: SAXParseException) = throw e
        })
        return try {
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: SAXParseException) {
            fail("not well-formed XML: ${e.message}")
        }
    }

    private fun everything(text: String): Notification = Notification(
        title = text,
        body = text,
        urgency = Urgency.CRITICAL,
        timeout = Timeout.Never,
        actions = listOf(NotificationAction(id = "id $text", label = "label $text")),
        tag = "tag",
    )

    @Test
    fun `a toast with every field set parses and keeps the text`() {
        val text = "a & b < c > d \" e ' f, Привет \uD83D\uDE00"
        val doc = parse(ToastXml.build(everything(text), imageUri = "file:///C:/a%20b/icon.png"))
        val texts = doc.getElementsByTagName("text")
        texts.item(0).textContent shouldBe text
        texts.item(1).textContent shouldBe text
        val action = doc.getElementsByTagName("action").item(0).attributes
        action.getNamedItem("content").nodeValue shouldBe "label $text"
        action.getNamedItem("arguments").nodeValue shouldBe "id $text"
    }

    @Test
    fun `control characters do not break the payload`() = knownGap("characters XML 1.0 forbids are passed through") {
        for (text in listOf("bell\u0007", "escape\u001B[31m", "vertical tab\u000B", "nul\u0000")) {
            parse(ToastXml.build(everything(text), imageUri = null))
        }
    }

    @Test
    fun `a lone surrogate does not break the payload`() = knownGap("characters XML 1.0 forbids are passed through") {
        parse(ToastXml.build(everything("half an emoji \uD83D"), imageUri = null))
    }
}
