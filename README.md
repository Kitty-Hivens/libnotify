<div align="center">
  <h1>libnotify</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache_2.0-86dbd7?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-22+-BB86FC?style=for-the-badge&logo=openjdk&logoColor=D9E0EE&labelColor=1E202B)](#)
[![Platform](https://img.shields.io/badge/Linux%20%7C%20Windows%20%7C%20macOS-supported-86dbce?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](#)

</div>

<div align="center">
  <h3>Cross-platform desktop notifications for JVM 22+ via Project Panama.</h3>
</div>

---

System notifications -- the OS banner / notification-center kind, **not** a tray
icon. One small API over three native backends, reached through pure
`java.lang.foreign` (Project Panama) bindings: no JNI, no JNA, no GLib/GTK
pull-in on Linux, no WinRT projection on Windows, no Swift on macOS. Small enough
to read end-to-end in one sitting.

A sibling to [`libtray`](https://github.com/Kitty-Hivens/libtray): same binding
discipline, the opposite surface -- libtray owns a tray icon, libnotify posts
notifications. Use both, or either.

<details>
  <summary>Platform backends</summary>

| Platform | Backend | Notes |
|---|---|---|
| Linux   | `org.freedesktop.Notifications` over D-Bus | Talks to the desktop's notification daemon directly via libdbus (Dunst, Mako, Fnott, Plasma, GNOME Shell, Xfce, Quickshell). No GLib / libnotify-C runtime dependency. Full surface: actions, urgency, inline images, replace-in-place, and `ActionInvoked` / `NotificationClosed` signals. |
| Windows | `Windows.UI.Notifications.ToastNotification` (WinRT) | The real system toast, driven through raw WinRT activation in `combase.dll`. **Requires a registered AUMID** (an installed Start-menu shortcut, or COM server, carrying the app id) -- the shell silently drops toasts under an unregistered id. |
| macOS   | `NSUserNotificationCenter` via the Objective-C runtime | Works from a plain `java ...` process -- no signed `.app` bundle, no entitlements. Deprecated by Apple since macOS 11 but still functional; the modern `UNUserNotificationCenter` path (which needs a bundle) is detected and planned. |

Each backend lives in its own package so consumers can audit / patch the one
that affects them without grokking the others.
</details>

<details>
  <summary>Install</summary>

```kotlin
dependencies {
    implementation("dev.hivens:libnotify:0.1.0")
}
```

Requires JDK 22+ (Project Panama). Caller must pass
`--enable-native-access=ALL-UNNAMED` (or grant the library's module
specifically) to permit the native calls.
</details>

<details>
  <summary>Use</summary>

```kotlin
import dev.hivens.libnotify.*

val notifier = Notifier.create(
    NotifierConfig(
        appName = "MyApp",
        appId = "Kitty-Hivens.MyApp",            // Windows AUMID; ignored elsewhere
        defaultIconBytes = Files.readAllBytes(Path.of("icon.png")),
    ),
) ?: return  // notifications not available here -- degrade gracefully

notifier.onEvent { event ->
    when (event) {
        is NotificationEvent.Activated     -> focusMainWindow()
        is NotificationEvent.ActionInvoked -> when (event.actionId) {
            "reply" -> openReply(event.notificationId)
            "mute"  -> mute(event.notificationId)
        }
        is NotificationEvent.Dismissed     -> Unit
    }
}

val handle = notifier.notify(
    Notification(
        title = "Download finished",
        body = "nexira-2.4.0.zip is ready.",
        urgency = Urgency.NORMAL,
        actions = listOf(
            NotificationAction(id = "open", label = "Open"),
            NotificationAction(id = "folder", label = "Show in folder"),
        ),
        tag = "download-nexira-240",   // posting again with this tag replaces the banner in place
    ),
)

// later, to take it down yourself:
handle?.let { notifier.cancel(it) }

// on shutdown
notifier.close()
```

Read [`NotifierCapabilities`](src/main/kotlin/dev/hivens/libnotify/NotifierCapabilities.kt)
(`notifier.capabilities`) to adapt before posting -- on Linux it reflects the
running daemon's `GetCapabilities`, on Windows and macOS it is fixed. A backend
silently drops what it cannot do (an extra action, an image) rather than failing
the post.
</details>

<details>
  <summary>Behaviour contract</summary>

- **Degrade, don't throw.** `Notifier.create` returns null when no backend is
  available; `notify` returns null and `cancel` returns false on failure.
  Notifications are non-essential UX -- a daemon restart or an unregistered
  AUMID becomes "no banner", never a crashed host.
- **Events run on a backend thread.** `onEvent` handlers fire on the D-Bus pump
  (Linux), a WinRT threadpool thread (Windows), or the Cocoa run loop (macOS).
  Hop to your UI thread yourself before touching UI state.
- **Replace by tag.** A [`Notification.tag`] reused while the prior banner is
  live replaces it in place where the platform supports it.
- **`Activated` vs `ActionInvoked`.** A click on the body is `Activated`; a
  click on a button is `ActionInvoked` with that action's id.
</details>

<details>
  <summary>Status</summary>

**Pre-1.0.** API may still shift before 1.0.

- **Linux** -- verified end-to-end against a live daemon (post, replace, cancel,
  capabilities, and the `NotificationClosed` signal round-trip). The primary
  downstream is the Nexira launcher on Hyprland.
- **Windows** -- toast show / replace / cancel and the activation-callback path
  are implemented to the WinRT ABI; on-metal verification on Win10/Win11 is
  pending (the libtray model: foundation first, tester-verified after).
- **macOS** -- the `NSUserNotification` backend is implemented (post, replace,
  cancel, icon, actions, activation delegate); on-device verification is
  pending. The modern `UNUserNotificationCenter` backend is planned for bundled,
  signed apps.
</details>

---

> Apache License 2.0 -- fork it, ship it, sell it. Patches welcome but not required.
