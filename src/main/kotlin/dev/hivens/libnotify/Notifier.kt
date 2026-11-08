package dev.hivens.libnotify

import dev.hivens.libnotify.linux.FreedesktopNotifier
import dev.hivens.libnotify.macos.NsUserNotificationNotifier
import dev.hivens.libnotify.windows.ToastNotifier
import org.slf4j.LoggerFactory

/**
 * A handle to the desktop's notification surface for this process.
 *
 * Created via [Notifier.create]; closed via [close] (or auto-closed with
 * `use { }`). Post notifications with [notify], cancel them with [cancel],
 * react to clicks via [onEvent].
 *
 * Threading: any thread may call the methods. Every event of one notifier is
 * delivered on one thread the notifier owns, one at a time and in the order the
 * notifier learned of them, on every platform: never on the caller's thread, the
 * Cocoa main thread or a Windows threadpool thread. So a listener needs no
 * locking for state only listeners touch. A consumer that wants to touch UI
 * state hops to its UI thread itself, for example with
 * `SwingUtilities.invokeLater` or a coroutine launched on `Dispatchers.Main`.
 *
 * No-throw philosophy: notifications are a non-essential UX surface. [notify]
 * returns null and [cancel] returns false on failure rather than throwing, so a
 * transient backend fault (D-Bus daemon restart, unregistered AUMID) degrades
 * to "no banner" instead of taking down the host application.
 */
public interface Notifier : AutoCloseable {

    /** True between successful construction and [close]. */
    public val isOpen: Boolean

    /**
     * What this backend can do -- action support, replacement, dismissal
     * events, and so on. Stable for the notifier's lifetime; on Linux it
     * reflects the notification server that was running at [create] time.
     */
    public val capabilities: NotifierCapabilities

    /**
     * Post a notification. Returns a [NotificationHandle] whose [id] every
     * resulting [NotificationEvent] carries, or null if the backend could not
     * post it (closed notifier, server error, no answer in time). When
     * [Notification.tag] is set and a notification with that tag is still live,
     * this replaces it in place where the backend supports replacement.
     *
     * A handle means the notification service accepted the post, not that anyone
     * saw it: do-not-disturb, for one, accepts and shows nothing. Null means it
     * was not accepted, with one limit: a request already sent cannot be recalled,
     * so a service that answers after the timeout can still show it.
     *
     * Blocks the calling thread until the service answers. Against a hung service
     * that is up to several seconds, so a UI thread should call this elsewhere,
     * for example `withContext(Dispatchers.IO) { notifier.notify(n) }`.
     */
    public fun notify(notification: Notification): NotificationHandle?

    /**
     * Dismiss a notification posted earlier. Returns true if the backend
     * accepted the request. A no-op (returns false) on a closed notifier or an
     * unknown handle.
     */
    public fun cancel(handle: NotificationHandle): Boolean

    /**
     * Subscribe to notification events. The listener runs on the backend's
     * dispatch thread (see class KDoc), and may call [notify], [cancel] and
     * [close] from there. Close the returned [Subscription] to stop delivery.
     */
    public fun onEvent(listener: NotificationListener): Subscription

    /**
     * Release backend resources. Cancels nothing already on screen -- shown
     * notifications outlive the process per platform convention. Idempotent.
     */
    public override fun close()

    public companion object {
        private val log = LoggerFactory.getLogger(Notifier::class.java)

        /**
         * Detect the host OS, instantiate the matching backend, and return a
         * live [Notifier]. Returns null when no backend is available -- the OS
         * isn't recognised, the platform's notification service isn't running,
         * or the backend's native libraries can't be resolved. Treat null as
         * "notifications not available here, degrade gracefully" (skip the
         * banner, don't crash).
         */
        @JvmStatic
        public fun create(config: NotifierConfig): Notifier? {
            val osName = System.getProperty("os.name", "").lowercase()
            val backend: Notifier? = runCatching {
                when {
                    osName.contains("linux") || osName.contains("bsd") -> FreedesktopNotifier.create(config)
                    osName.contains("windows") -> ToastNotifier.create(config)
                    osName.contains("mac") || osName.contains("darwin") -> NsUserNotificationNotifier.create(config)
                    else -> null
                }
            }.onFailure {
                log.info("Notifier backend construction failed: {}", it.message ?: it.javaClass.simpleName)
            }.getOrNull()
            return backend.also {
                if (it == null) log.info("Notifier unavailable for os.name={}", osName)
            }
        }
    }
}
