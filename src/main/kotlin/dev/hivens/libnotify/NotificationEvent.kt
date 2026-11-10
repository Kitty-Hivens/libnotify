package dev.hivens.libnotify

/**
 * Something the user (or the system) did to a posted notification.
 * Subscribed to via [Notifier.onEvent].
 *
 * Every event carries the [notificationId]: the [NotificationHandle.id]
 * returned by the [Notifier.notify] call that posted it (which equals the
 * [Notification.tag] when one was given). Match it against handles you kept to
 * know which notification fired.
 *
 * Backend coverage varies and is reported by [NotifierCapabilities]:
 * [Activated] and [ActionInvoked] fire wherever the platform reports activation
 * at all. [Dismissed] after [Notifier.cancel] fires everywhere, other dismissals
 * only where [NotifierCapabilities.closeEvents] is true (freedesktop and Windows
 * toast, while macOS `NSUserNotification` has no reliable dismissal callback).
 *
 * Later versions may add event types, properties on these ones, and
 * [DismissReason] values. Switch on what you care about and keep an `else`
 * branch, so a new type passes through instead of breaking the build. The
 * constructors are public so a consumer can feed its own listener in a test.
 */
// The event classes are not data classes, for the reason given on
// Notification: a property added later, an activation token on Activated for
// one, would otherwise move copy and componentN and break compiled callers.
public sealed interface NotificationEvent {

    /** The notification this event concerns. */
    public val notificationId: String

    /**
     * The notification body was clicked (no specific action button). Most apps
     * wire this to "focus the relevant window". freedesktop delivers it as the
     * reserved `default` action, Windows toast as a body activation with the
     * launch arguments, macOS as `contentsClicked`.
     */
    public class Activated(
        override val notificationId: String,
    ) : NotificationEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Activated && notificationId == other.notificationId)

        override fun hashCode(): Int = notificationId.hashCode()

        override fun toString(): String = "Activated(notificationId=$notificationId)"
    }

    /**
     * An action button was clicked. [actionId] matches the
     * [NotificationAction.id] the consumer set when building the notification.
     */
    public class ActionInvoked(
        override val notificationId: String,
        public val actionId: String,
    ) : NotificationEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is ActionInvoked && notificationId == other.notificationId && actionId == other.actionId)

        override fun hashCode(): Int = 31 * notificationId.hashCode() + actionId.hashCode()

        override fun toString(): String = "ActionInvoked(notificationId=$notificationId, actionId=$actionId)"
    }

    /**
     * The notification left the screen, for any reason. [reason] tells a timeout,
     * the user, and [Notifier.cancel] apart where the platform reports it.
     *
     * A successful [Notifier.cancel] produces exactly one, with reason
     * [DismissReason.CLOSED], on every platform. Called from anywhere but a
     * listener, cancel delivers it before returning, so it comes before any event
     * of a notification posted after the cancel, even one reusing the same tag.
     * Called from a listener, it is delivered right after the current event has
     * reached every listener, ahead of any other event, because listeners are
     * never entered twice at once.
     */
    public class Dismissed(
        override val notificationId: String,
        public val reason: DismissReason,
    ) : NotificationEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Dismissed && notificationId == other.notificationId && reason == other.reason)

        override fun hashCode(): Int = 31 * notificationId.hashCode() + reason.hashCode()

        override fun toString(): String = "Dismissed(notificationId=$notificationId, reason=$reason)"
    }
}

/**
 * Why a [NotificationEvent.Dismissed] fired.
 *
 * Maps the freedesktop `NotificationClosed` reason codes and the Windows
 * `ToastDismissalReason` enum onto a common set. Backends that cannot
 * distinguish report [UNKNOWN]. Later versions may add values.
 */
public enum class DismissReason {
    /** Auto-dismissed because its timeout elapsed. */
    EXPIRED,

    /** The user actively dismissed it (swiped/closed the banner). */
    DISMISSED_BY_USER,

    /** Closed by [Notifier.cancel]. */
    CLOSED,

    /** The platform reported a dismissal without a distinguishable reason. */
    UNKNOWN,
}
