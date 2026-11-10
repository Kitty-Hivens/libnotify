# Changelog

All notable changes to libnotify will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.2.0]

### Fixed
- Linux: the scratch buffer for `DBusMessageIter` is aligned for the pointers
  libdbus writes into it. It was declared as bytes, so nothing but malloc's own
  generosity kept it aligned.
- Linux: a notification id counts only from the server that issued it. Any
  client on the session bus could send `ActionInvoked` and pass for a click, a
  click on an action the notification never offered was passed on, and after a
  notification daemon restart the old ids were offered to the new server for
  replacement and its notifications, reusing the same numbers, reported as ours.
  Ids are now paired with the issuing server's unique bus name, signals count
  only from that name and the service's object path, actions are checked against
  the post, and the body-markup stance is asked again of a new server.
- Linux: a notifier survives a restart of the session bus. The next call opens a
  new connection, so the host application has nothing to do. It used to stay
  dead for good while `isOpen` kept reporting true. Notifications posted before
  the restart belong to the old bus, so they report no more events and cannot be
  cancelled.
- Linux: icons are decoded in memory. `ImageIO.read` on a stream cached the
  icon bytes in a temporary file on disk for the length of the decode.
- Linux: an icon whose header claims more than 4096 by 4096 pixels is dropped
  before decoding, where a small file could make the decoder allocate gigabytes.
  Larger ones within that are scaled to 256 on their longest side, since servers
  draw them at a few dozen pixels and every pixel is four bytes in the message.
- Linux: icons are decoded on the calling thread instead of the dispatch thread,
  where a large one held up every other call and event, and the default icon is
  decoded once instead of on every post.
- Linux: `Notification.body` is plain text on servers that parse markup too. It
  went out verbatim, so such a server treated `report<final>.pdf` as a tag and
  dropped part of the text, and tags in third-party text reached the server as
  markup. The body is now escaped when the server advertises `body-markup`. A
  server absent at create time is asked again on each post until one answers.
- Linux: `notify`, `cancel` and `close` work from inside an `onEvent` handler.
  The handler runs on the dispatch thread, so `notify` waited six seconds on
  itself, returned null, and the banner appeared afterwards. `close` waited out
  its own join and leaked the connection.
- Linux, Windows: null from `notify` no longer covers a post that goes out
  later. A call the dispatch thread has not started within a second is dropped
  unsent. `close()` releases queued callers at once instead of leaving them to
  time out, and an interrupted caller keeps its interrupt flag.
- Linux, Windows: an exception inside `notify` or `cancel` is logged. It used to
  turn into null or false with no trace.
- Windows: a failed start ran the teardown twice, calling `RoUninitialize` one
  more time than `RoInitialize`.
- Linux: `close()` no longer frees the connection while the dispatch thread may
  still be inside a libdbus call, which is a segfault inside libdbus that takes
  the host process with it. That thread now frees the connection itself as it
  exits, so a slow daemon delays the release instead of leaking the connection.
- Linux: `cancel()` returns false when the daemon gave no reply. It returned
  true unconditionally, so a caller was told the banner had been taken down when
  nothing had heard the request -- `Notifier.cancel` documents true as meaning
  the backend accepted it.
- Linux: `create()` releases the Panama arena when there is no session bus, and
  releases both the arena and the private connection if setup throws after the
  connection is open. Neither had any other owner: no `Notifier` exists on those
  paths, so nothing would ever have called `close()`.

### Changed
- Breaking: `Notification` and `NotifierConfig` are plain classes instead of data
  classes, so a new property no longer breaks binary compatibility. `copy` and the
  `componentN` functions are gone. `toBuilder()` replaces `copy`, and each class
  has a `Builder` for Java callers.
- Breaking: `Notifier.onEvent` takes a `NotificationListener` and returns a
  `Subscription` to close, instead of Kotlin function types. Kotlin lambdas still
  convert. Closing a subscription removes that registration only, so a listener
  registered twice keeps its other registration.
- Breaking: `NotifierCapabilities` is a plain class built through
  `NotifierCapabilities.Builder`, which starts with every capability off. Its
  constructor, `copy` and `componentN` are gone, so a new capability no longer
  breaks binary compatibility. Reading it is unchanged.
- Breaking: the event classes `Activated`, `ActionInvoked` and `Dismissed` are
  plain classes. Their constructors stay public, so a consumer can still feed
  its own listener in a test, and they still compare by value. `copy` and the
  `componentN` functions are gone, so a property added later, such as an
  activation token, breaks no compiled caller. New event types and
  `DismissReason` values may come too: keep an `else` branch.
- Breaking: `NotificationHandle` is a plain class that belongs to the notifier
  that issued it. `cancel` on another notifier returns false even for the same
  id: two notifiers posting the same tag could cancel each other's banner. Its
  new public constructor makes a handle for a `Notifier` test double, which a
  real notifier refuses.
- Generated ids carry a random prefix per notifier. They counted `ln-0`, `ln-1`
  and so on in every notifier and every run, so they matched an obvious tag,
  each other, and on Windows and macOS the previous run's banners, which a new
  run then replaced.
- Breaking: `NotificationAction` is a plain class too. Its constructor and
  equality are unchanged, `copy` and the `componentN` functions are gone.
- `Notification` and `NotifierConfig` copy their icon bytes on the way in and on
  every read, and `Notification.actions` is a read-only copy. Changing the array
  or list given to the constructor, or one a getter returned, used to change the
  notification after it was built, its hash code included.
- Implementing `Notifier`, for a test double, is now documented as supported. A
  method added to the interface later comes with a default implementation.
- Moving from 0.1.x:
  - `val unsubscribe: () -> Unit = notifier.onEvent { ... }` and
    `unsubscribe()` become `val subscription: Subscription = notifier.onEvent { ... }`
    and `subscription.close()`. The lambda itself is unchanged.
  - `notification.copy(title = "x")` becomes
    `notification.toBuilder().title("x").build()`, the same for `NotifierConfig`.
  - In a test double, `NotifierCapabilities(false, 0, ...)` becomes
    `NotifierCapabilities.Builder().build()`, plus a setter per capability the
    double should claim, and `override fun onEvent(handler: (NotificationEvent) -> Unit): () -> Unit`
    becomes `override fun onEvent(listener: NotificationListener): Subscription`.
- `Notifier.create` is a static method for Java callers, and the empty
  `NotificationAction.Companion` is gone.
- A successful `cancel` produces exactly one `Dismissed(CLOSED)` on every
  platform, delivered before `cancel` returns, or right after the current event
  when called from a listener. Linux sent it whenever the server's signal got
  read, after a repost of the same tag could already have taken the old id, so
  it went missing or landed on the new notification. Windows raced its own
  callback and macOS sent none. The KDoc said cancel produced no `Dismissed` at
  all while also documenting `CLOSED` as the reason for one.
- Every event of a notifier is delivered on one thread the notifier owns, one at
  a time and in order, on every platform, and a listener is never entered twice
  at once. Windows called listeners from WinRT threadpool threads, concurrently
  with each other, and macOS from the Cocoa main thread and from whichever thread
  called `cancel`. Only Linux kept the order the KDoc promised.
- Windows: a toast's events are matched to it by COM identity instead of by tag.
  A late callback from a cancelled or replaced toast landed on the newer toast
  with the same tag, which then lost its events and could no longer be
  cancelled, and a listener that reposted the tag of a dismissed toast lost the
  new one the same way. `close()` from a listener no longer waits three seconds
  on its own thread.
- macOS: a `notify` that raced `close()` no longer posts a banner and retains it
  in a notifier that has already let go.
- macOS: a delegate the host already had on `NSUserNotificationCenter` is kept
  and put back when the last notifier closes, instead of being replaced for good
  and then cleared to nil. Creating and closing notifiers at the same time can no
  longer leave an open one without a delegate.
- The `Notifier.create` KDoc says when it returns null on each platform. It
  promised null without a running notification service, while on Linux a
  session bus is enough and a server that starts later is picked up.
- The `appId` KDoc says what Linux does with it: it goes out as the
  `desktop-entry` hint. It said the opposite.
- Built with Kotlin 2.4.21. The library depends on its standard library and on
  `slf4j-api` 2.0.20, which consumers get transitively.
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
