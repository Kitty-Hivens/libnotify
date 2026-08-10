# Changelog

All notable changes to libnotify will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Fixed
- Linux: `close()` no longer frees the connection while the dispatch thread may
  still be inside a libdbus call. The join budget is two seconds, but that
  thread can sit in `dbus_connection_send_with_reply_and_block` for the full
  five-second reply timeout, so the join returning proved nothing; unreffing the
  connection under a live `dbus_*` call is a segfault inside libdbus that takes
  the host process with it. When the thread is still alive the teardown is
  skipped and the connection leaks instead, which is the cheaper failure.
- Linux: `cancel()` returns false when the daemon gave no reply. It returned
  true unconditionally, so a caller was told the banner had been taken down when
  nothing had heard the request -- `Notifier.cancel` documents true as meaning
  the backend accepted it.
- Linux: `create()` releases the Panama arena when there is no session bus, and
  releases both the arena and the private connection if setup throws after the
  connection is open. Neither had any other owner: no `Notifier` exists on those
  paths, so nothing would ever have called `close()`.

### Changed
- `SmokeMain` moved into the test source set so it no longer ships in the
  published library jar. It was packaged in 0.1.0 through 0.1.2, so the next
  release drops `dev.hivens.libnotify.SmokeMainKt` from the artifact -- it was
  an interactive harness with a `main`, never API.

## [0.1.2]

### Fixed
- Linux: the notification backend opens a private D-Bus connection
  (`dbus_bus_get_private`) instead of the process-shared `dbus_bus_get`
  one, and turns off `exit_on_disconnect`. Sharing one connection with
  another libdbus user that runs its own message pump (for instance a
  sibling tray library) let either side pop and drop messages destined
  for the other off the single shared incoming queue. `close()` now
  closes the private connection before the final unref. (Same root
  cause as the libtray 0.1.2 fix; the D-Bus binding was shared in shape.)

## [0.1.1]

### Fixed
- Linux: the `DBusMessageIter` scratch buffer was 64 bytes, but the
  struct is 72 on x86_64 / aarch64 -- libdbus wrote its trailing pointer
  8 bytes past the allocation on every `dbus_message_iter_*` call,
  silently corrupting adjacent arena memory. Reserved 80. (Same root
  cause as the libtray 0.1.1 fix; the D-Bus binding was shared in shape.)
- Linux: `Notifier.close()` closes the Panama arena (library lookup +
  downcall handles) after the dispatch thread joins, so repeated
  create/close cycles no longer leak native memory.

## [0.1.0]

### Added
- Initial cross-platform notification API: `Notifier` (facade + `create`),
  `Notification`, `NotificationAction`, `NotificationEvent`
  (`Activated` / `ActionInvoked` / `Dismissed`), `NotificationHandle`,
  `NotifierConfig`, `NotifierCapabilities`, `Urgency`, `Timeout`. Pure
  `java.lang.foreign` (Project Panama), JDK 22 floor, SLF4J for logging,
  no-throw degradation throughout.
- Linux backend (`org.freedesktop.Notifications` over libdbus): `Notify`,
  `CloseNotification`, `GetCapabilities`; the `image-data` hint for inline
  RGBA icons; urgency and expire-timeout hints; replace-in-place via
  `replaces_id`; an `AddMatch` signal subscription with a single-thread
  dispatch loop turning `ActionInvoked` / `NotificationClosed` into events.
  All D-Bus access is serialised onto one connection-owning thread.
- Windows backend (`Windows.UI.Notifications.ToastNotification` via WinRT in
  combase): XML toast payload, `CreateToastNotifierWithId` (AUMID), `Show` /
  `Hide`, `Tag`-based replace, action buttons, urgency-to-scenario and
  timeout-to-duration mapping, and synthesised COM event handlers
  (`add_Activated` / `add_Dismissed`) routed by `Tag`.
- macOS backend (`NSUserNotificationCenter` via the Objective-C runtime):
  post, replace by identifier, cancel, content image, a primary action button
  plus an alternate-action menu, and a runtime delegate class for activation
  events. Bundle-free; detects a bundled `UNUserNotificationCenter`
  environment for the planned modern backend.
- `runSmoke` Gradle task: an interactive harness that posts a notification with
  action buttons and prints activation events.

### Notes
- Linux is verified end-to-end against a live daemon. Windows and macOS are
  implemented to their platform ABIs with on-device verification pending.
- The modern macOS `UNUserNotificationCenter` backend (for signed, bundled
  apps) is not yet implemented; the current macOS backend uses the
  bundle-free `NSUserNotification` API.
