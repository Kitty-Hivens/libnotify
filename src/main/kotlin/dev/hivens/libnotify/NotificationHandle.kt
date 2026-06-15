package dev.hivens.libnotify

/**
 * A reference to a posted notification, returned by [Notifier.notify].
 *
 * Carries the [id] that every [NotificationEvent] for this notification
 * reports, and that [Notifier.cancel] takes to dismiss it. When the posting
 * [Notification] supplied a [Notification.tag], [id] equals that tag (so a
 * later post with the same tag replaces this one); otherwise the library
 * assigned a fresh unique id.
 *
 * Deliberately a thin value -- it holds no backend reference, so keeping one
 * around cannot pin native resources. Cancellation goes back through the
 * owning [Notifier].
 */
@ConsistentCopyVisibility
public data class NotificationHandle internal constructor(
    val id: String,
)
