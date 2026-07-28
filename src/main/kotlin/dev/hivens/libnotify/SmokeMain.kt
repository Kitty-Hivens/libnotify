package dev.hivens.libnotify

import dev.hivens.libnotify.macos.ObjcBindings
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.foreign.MemorySegment
import java.util.concurrent.CountDownLatch
import javax.imageio.ImageIO

/**
 * Manual smoke for the notification backend. Run via `./gradlew runSmoke` --
 * posts a notification with two action buttons and prints activation events to
 * stdout. Ctrl-C to exit (clicking the "Exit" action also ends it).
 *
 * Interactive, not a unit test: it exercises the live OS notification surface,
 * which CI cannot probe.
 *
 * **Validation checklist** (use as a script when bringing up a platform):
 *
 *   1. Run `./gradlew runSmoke`. Stdout prints the resolved capabilities and
 *      "notification posted" within a second or two.
 *   2. A banner appears: title "libnotify smoke", body text, the magenta icon,
 *      and two buttons -- "Say hi" and "Exit".
 *   3. Click the banner body. Stdout prints `event: Activated`.
 *   4. Click "Say hi". Stdout prints `event: ActionInvoked id=hi`.
 *   5. Click "Exit" (or let it time out / dismiss). "Exit" prints
 *      `id=exit` and the program terminates; a timeout/dismissal prints
 *      `event: Dismissed` where the platform reports it.
 *
 * Per-OS prerequisites are in the failure message below.
 */
public fun main() {
    println("libnotify smoke -- building a 64x64 PNG icon in memory...")
    val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
        val g = createGraphics()
        try {
            g.color = Color(0xBB, 0x86, 0xFC)  // matches Nexira's primary
            g.fillOval(4, 4, 56, 56)
            g.color = Color.BLACK
            g.fillRect(28, 22, 8, 16)
            g.fillRect(28, 42, 8, 8)
        } finally {
            g.dispose()
        }
    }
    val png = ByteArrayOutputStream().also { ImageIO.write(img, "PNG", it) }.toByteArray()

    val notifier = Notifier.create(
        NotifierConfig(
            appName = "libnotify smoke",
            appId = "dev.hivens.libnotify.smoke",  // Windows AUMID -- register a shortcut for toasts to show
            defaultIconBytes = png,
        ),
    ) ?: run {
        System.err.println("Notifier.create returned null. ${platformHint()}")
        kotlin.system.exitProcess(1)
    }

    println("[smoke] capabilities: ${notifier.capabilities}")

    val exit = CountDownLatch(1)
    notifier.onEvent { event ->
        when (event) {
            is NotificationEvent.Activated -> println("[smoke] event: Activated id=${event.notificationId}")
            is NotificationEvent.ActionInvoked -> {
                println("[smoke] event: ActionInvoked id=${event.notificationId} action=${event.actionId}")
                if (event.actionId == "exit") exit.countDown()
            }
            is NotificationEvent.Dismissed ->
                println("[smoke] event: Dismissed id=${event.notificationId} reason=${event.reason}")
        }
    }

    val handle = notifier.notify(
        Notification(
            title = "libnotify smoke",
            body = "Click a button, or the body, to fire an event.",
            urgency = Urgency.NORMAL,
            actions = listOf(
                NotificationAction(id = "hi", label = "Say hi"),
                NotificationAction(id = "exit", label = "Exit"),
            ),
            tag = "smoke-1",
        ),
    )
    if (handle == null) {
        System.err.println("notify() returned null -- the server rejected the post. ${platformHint()}")
        kotlin.system.exitProcess(1)
    }
    println("[smoke] notification posted: id=${handle.id}. Interact with it, or Ctrl-C to exit.")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("[smoke] shutdown -> notifier.close()")
        runCatching { notifier.close() }
    })

    val os = System.getProperty("os.name", "").lowercase()
    if (os.contains("mac") || os.contains("darwin")) {
        runMacCocoaLoopUntil(exit) { notifier.close() }
    } else {
        exit.await()
        notifier.close()
    }
}

private fun platformHint(): String {
    val os = System.getProperty("os.name", "").lowercase()
    return when {
        os.contains("linux") || os.contains("bsd") ->
            "Linux: a session bus + a notification daemon (Dunst, Mako, Plasma, GNOME Shell) must be reachable. " +
                "Check DBUS_SESSION_BUS_ADDRESS is set and a daemon is running."
        os.contains("windows") ->
            "Windows: needs JDK 22+ with --enable-native-access=ALL-UNNAMED, and a registered AUMID " +
                "(an installed Start-menu shortcut carrying the app id) for toasts to actually appear."
        os.contains("mac") || os.contains("darwin") ->
            "macOS: run with -XstartOnFirstThread (the runSmoke task adds it) so the Cocoa run loop delivers banners."
        else -> "Unrecognised OS: $os. libnotify supports Linux, Windows, macOS."
    }
}

/**
 * macOS smoke exit path. `NSUserNotificationCenter` delivers banners and
 * activation callbacks through the Cocoa main run loop; a headless process must
 * run `[NSApp run]` on the `-XstartOnFirstThread` main thread or nothing is
 * delivered. A background watcher closes the notifier and `[NSApp terminate:]`s
 * when the "Exit" action fires. A real host UI toolkit owns this loop already.
 */
private fun runMacCocoaLoopUntil(exit: CountDownLatch, close: () -> Unit) {
    val bindings = ObjcBindings.load() ?: run {
        System.err.println("[smoke] ObjcBindings.load() failed -- cannot run the Cocoa loop")
        exit.await(); close(); return
    }
    val nsAppCls = bindings.cls("NSApplication")
    val app = bindings.handle("objc_msgSend_id")
        .invokeExact(nsAppCls, bindings.sel("sharedApplication")) as MemorySegment

    Thread({
        exit.await()
        runCatching { close() }
        runCatching {
            bindings.handle("objc_msgSend_void_id")
                .invokeExact(app, bindings.sel("terminate:"), MemorySegment.NULL) as Unit
        }
    }, "libnotify-smoke-exit-watcher").apply { isDaemon = false }.start()

    println("[smoke] entering [NSApp run] on the main thread")
    bindings.handle("objc_msgSend_void").invokeExact(app, bindings.sel("run")) as Unit
}
