package dev.hivens.libnotify.it

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.NotifierConfig
import dev.hivens.libnotify.Timeout
import dev.hivens.libnotify.Urgency
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** What a post puts on the wire, and the handle it returns. */
internal class PostingIT : BusTest() {

    @Test
    fun `a post carries the app name, title and body`() {
        val server = daemon()
        notifier(NotifierConfig(appName = "My App")).notify(Notification(title = "Hello", body = "World"))
        val got = server.awaitNotify()
        got.appName shouldBe "My App"
        got.summary shouldBe "Hello"
        got.body shouldBe "World"
    }

    @Test
    fun `text survives in any script`() {
        val server = daemon(capabilities = listOf("body"))
        val title = "Привет, 世界 😀"
        val body = "вторая строка\nand a third"
        notifier().notify(Notification(title = title, body = body))
        val got = server.awaitNotify()
        got.summary shouldBe title
        got.body shouldBe body
    }

    @Test
    fun `a tagged post returns the tag as its handle id`() {
        daemon()
        notifier().notify(titled("t", tag = "my-tag"))?.id shouldBe "my-tag"
    }

    @Test
    fun `the app id goes out as the desktop entry, which servers group by`() {
        val server = daemon()
        val notifier = notifier(NotifierConfig(appName = "a", appId = "com.example.App"))
        notifier.notify(titled("t"))
        server.awaitNotify().hints["desktop-entry"] shouldBe "com.example.App"
    }

    @Test
    fun `no app id sends no desktop entry`() {
        val server = daemon()
        notifier().notify(titled("t"))
        server.awaitNotify().hints.containsKey("desktop-entry") shouldBe false
    }

    @Test
    fun `untagged posts get distinct ids and do not replace each other`() {
        val server = daemon()
        val notifier = notifier()
        val first = notifier.notify(titled("one"))
        val second = notifier.notify(titled("two"))
        first shouldNotBe null
        second shouldNotBe null
        first?.id shouldNotBe second?.id
        server.awaitNotify().replacesId shouldBe 0
        server.awaitNotify().replacesId shouldBe 0
    }

    @Test
    fun `a tag a caller would pick does not collide with a generated id`() {
        val server = daemon()
        val notifier = notifier()
        val generated = notifier.notify(titled("generated")) ?: error("the post failed, so this proves nothing")
        server.awaitNotify()
        // The ids 0.1.x generated, and the obvious guesses at them.
        for (tag in listOf("ln-0", "ln-1", "0", "1")) {
            notifier.notify(titled("tagged", tag = tag)) shouldNotBe null
            server.awaitNotify().replacesId shouldBe 0
        }
        generated.id shouldNotBe "ln-0"
    }

    @Test
    fun `generated ids differ between notifiers`() {
        daemon()
        val first = notifier().notify(titled("t")) ?: error("the first post failed, so this proves nothing")
        val second = notifier().notify(titled("t")) ?: error("the second post failed, so this proves nothing")
        first.id shouldNotBe second.id
        first.id.substringBeforeLast('-') shouldNotBe second.id.substringBeforeLast('-')
    }

    @Test
    fun `reposting a live tag replaces its banner`() {
        val server = daemon()
        val notifier = notifier()
        notifier.notify(titled("v1", tag = "t"))
        val first = server.awaitNotify()
        notifier.notify(titled("v2", tag = "t"))
        val second = server.awaitNotify()
        first.replacesId shouldBe 0
        second.replacesId shouldBe first.id
        second.id shouldBe first.id
    }

    @Test
    fun `different tags do not replace each other`() {
        val server = daemon()
        val notifier = notifier()
        notifier.notify(titled("a", tag = "a"))
        notifier.notify(titled("b", tag = "b"))
        server.awaitNotify().replacesId shouldBe 0
        server.awaitNotify().replacesId shouldBe 0
    }

    @Test
    fun `reposting a tag whose banner closed starts a new one`() {
        val server = daemon()
        val notifier = notifier()
        val events = notifier.record()
        notifier.notify(titled("v1", tag = "t"))
        server.closed(server.awaitNotify().id, 1)
        events.next()
        notifier.notify(titled("v2", tag = "t"))
        server.awaitNotify().replacesId shouldBe 0
    }

    @Test
    fun `urgency goes out as the spec byte`() {
        val server = daemon()
        val notifier = notifier()
        for ((urgency, byte) in listOf(Urgency.LOW to 0, Urgency.NORMAL to 1, Urgency.CRITICAL to 2)) {
            notifier.notify(Notification(title = "t", urgency = urgency))
            server.awaitNotify().hints["urgency"] shouldBe byte.toByte()
        }
    }

    @Test
    fun `timeout goes out as the spec value`() {
        val server = daemon()
        val notifier = notifier()
        for ((timeout, expire) in listOf(Timeout.ServerDefault to -1, Timeout.Never to 0, Timeout.After(4_000) to 4_000)) {
            notifier.notify(Notification(title = "t", timeout = timeout))
            server.awaitNotify().expireTimeout shouldBe expire
        }
    }

    @Test
    fun `category goes out as a hint only when set`() {
        val server = daemon()
        val notifier = notifier()
        notifier.notify(Notification(title = "t", category = "im.received"))
        server.awaitNotify().hints["category"] shouldBe "im.received"
        notifier.notify(Notification(title = "t"))
        server.awaitNotify().hints.containsKey("category") shouldBe false
    }

    @Test
    fun `actions go out in order after the default action`() {
        val server = daemon()
        notifier().notify(
            Notification(
                title = "t",
                actions = listOf(NotificationAction("open", "Open"), NotificationAction("later", "Later")),
            ),
        )
        server.awaitNotify().actions shouldBe listOf("default", "", "open", "Open", "later", "Later")
    }

    @Test
    fun `a post with no actions still registers the body click`() {
        val server = daemon()
        notifier().notify(titled("t"))
        server.awaitNotify().actions shouldBe listOf("default", "")
    }

    @Test
    fun `icon name goes out as the app icon and beats the default name`() {
        val server = daemon()
        val notifier = notifier(NotifierConfig(appName = "a", defaultIconName = "fallback"))
        notifier.notify(titled("t"))
        server.awaitNotify().appIcon shouldBe "fallback"
        notifier.notify(Notification(title = "t", iconName = "dialog-warning"))
        server.awaitNotify().appIcon shouldBe "dialog-warning"
    }

    @Test
    fun `no icon anywhere sends no image and an empty app icon`() {
        val server = daemon()
        notifier().notify(titled("t"))
        val got = server.awaitNotify()
        got.appIcon shouldBe ""
        got.image shouldBe null
    }

    @Test
    fun `icon bytes arrive as straight RGBA pixels, red first, alpha kept`() {
        val server = daemon()
        notifier().notify(Notification(title = "t", iconBytes = pngOf(0x80FF0000.toInt(), 0xFF00FF00.toInt())))
        val image = server.awaitNotify().image!!
        image.width shouldBe 2
        image.height shouldBe 1
        image.rowstride shouldBe 8
        image.hasAlpha shouldBe true
        image.bitsPerSample shouldBe 8
        image.channels shouldBe 4
        image.pixels.toList() shouldBe listOf<Byte>(-1, 0, 0, -128, 0, -1, 0, -1)
    }

    @Test
    fun `the default icon bytes fill in, and a notification's own bytes win`() {
        val server = daemon()
        val notifier = notifier(NotifierConfig(appName = "a", defaultIconBytes = pngOf(0xFF000000.toInt())))
        notifier.notify(titled("t"))
        server.awaitNotify().image!!.width shouldBe 1
        notifier.notify(Notification(title = "t", iconBytes = pngOf(0, 0, 0)))
        server.awaitNotify().image!!.width shouldBe 3
    }

    @Test
    fun `a big icon goes out scaled to 256 on its longest side, aspect kept`() {
        val server = daemon()
        notifier().notify(Notification(title = "t", iconBytes = pngOfSize(1024, 512)))
        val image = server.awaitNotify().image!!
        image.width shouldBe 256
        image.height shouldBe 128
        image.rowstride shouldBe 256 * 4
        image.pixels.size shouldBe 256 * 128 * 4
    }

    @Test
    fun `an icon claiming an enormous size posts without an image and without decoding it`() {
        val server = daemon()
        val notifier = notifier()
        elapsedMillis {
            notifier.notify(Notification(title = "t", iconBytes = pngClaiming(30_000, 30_000))) shouldNotBe null
        } shouldBeLessThan 2_000L
        val got = server.awaitNotify()
        got.summary shouldBe "t"
        got.image shouldBe null
    }

    @Test
    fun `decoding an icon never writes it to disk, not even for a moment`() {
        val server = daemon()
        val notifier = notifier()
        // Encoded before the watch starts: writing a PNG through ImageIO caches
        // to a file of its own, which is the test's doing, not the library's.
        val icon = Notification(title = "t", iconBytes = pngOfSize(64, 64))
        // ImageIO caches a stream it reads in a temporary file, deleted again on
        // close, so the file is gone afterwards either way. Watching its cache
        // directory catches the creation itself.
        val cacheDir = Files.createTempDirectory("imageio-cache")
        ImageIO.setCacheDirectory(cacheDir.toFile())
        FileSystems.getDefault().newWatchService().use { watch ->
            cacheDir.register(watch, StandardWatchEventKinds.ENTRY_CREATE)
            notifier.notify(icon)
            server.awaitNotify().image shouldNotBe null
            watch.poll(500, TimeUnit.MILLISECONDS) shouldBe null
        }
    }

    @Test
    fun `undecodable icon bytes still post the text`() {
        val server = daemon()
        val handle = notifier().notify(Notification(title = "t", iconBytes = byteArrayOf(1, 2, 3)))
        handle shouldNotBe null
        val got = server.awaitNotify()
        got.summary shouldBe "t"
        got.image shouldBe null
    }

    @Test
    fun `posts from many threads all arrive with their own ids`() {
        val server = daemon()
        val notifier = notifier()
        val pool = Executors.newFixedThreadPool(16)
        val ids = Collections.synchronizedSet(HashSet<String?>())
        repeat(160) { pool.submit { ids.add(notifier.notify(titled("t"))?.id) } }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS) shouldBe true
        ids shouldHaveSize 160
        ids.contains(null) shouldBe false
        repeat(160) { server.awaitNotify() }
        server.assertNoNotify()
    }
}
