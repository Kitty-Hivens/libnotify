package dev.hivens.libnotify

/**
 * Something the user (or the system) did to a posted notification.
 * Subscribed to via [Notifier.onEvent].
 *
 * Every event carries the [notificationId] -- the [NotificationHandle.id]
 * returned by the [Notifier.notify] call that posted it (which equals the
 * [Notification.tag] when one was given). Match it against handles you kept to
 * know which notification fired.
 *
 * Backend coverage varies and is reported by [NotifierCapabilities]:
 * [Activated] and [ActionInvoked] fire wherever the platform reports activation
 * at all; [Dismissed] fires only where [NotifierCapabilities.closeEvents] is
 * true (freedesktop and Windows toast; macOS `NSUserNotification` has no
 * reliable dismissal callback). Switch on what you care about and ignore the
 * rest rather than rely on exhaustive coverage.
 */
public sealed interface NotificationEvent {

    /** The notification this event concerns. */
    public val notificationId: String

    /**
     * The notification body was clicked (no specific action button). Most apps
     * wire this to "focus the relevant window". freedesktop delivers it as the
     * reserved `default` action; Windows toast as a body activation with the
     * launch arguments; macOS as `contentsClicked`.
     */
    public data class Activated(
        override val notificationId: String,
    ) : NotificationEvent

    /**
     * An action button was clicked. [actionId] matches the
     * [NotificationAction.id] the consumer set when building the notification.
     */
    public data class ActionInvoked(
        override val notificationId: String,
        val actionId: String,
    ) : NotificationEvent

    /**
     * The notification left the screen without the consumer cancelling it.
     * [reason] distinguishes a timeout from an explicit user dismissal where
     * the platform reports it.
     */
    public data class Dismissed(
        override val notificationId: String,
        val reason: DismissReason,
    ) : NotificationEvent
}

/**
 * Why a [NotificationEvent.Dismissed] fired.
 *
 * Maps the freedesktop `NotificationClosed` reason codes and the Windows
 * `ToastDismissalReason` enum onto a common set; backends that cannot
 * distinguish report [UNKNOWN].
 */
public enum class DismissReason {
    /** Auto-dismissed because its timeout elapsed. */
    EXPIRED,

    /** The user actively dismissed it (swiped/closed the banner). */
    DISMISSED_BY_USER,

    /** Closed programmatically, typically by [Notifier.cancel]. */
    CLOSED,

    /** The platform reported a dismissal without a distinguishable reason. */
    UNKNOWN,
}
