package dev.hivens.libnotify

/**
 * A button offered on a [Notification]. Clicking it fires
 * [NotificationEvent.ActionInvoked] carrying this action's [id].
 *
 * Backend coverage of actions varies -- read [NotifierCapabilities.actions]
 * and [NotifierCapabilities.maxActions]. Linux freedesktop supports an
 * unbounded list (servers cap visually); Windows toast allows up to five;
 * macOS `NSUserNotification` shows a single action button, so only the first
 * action survives there.
 *
 * @property id Stable identifier surfaced in [NotificationEvent.ActionInvoked].
 *   Must be non-blank and must not be [DEFAULT_ID] -- the default
 *   whole-notification click is delivered as [NotificationEvent.Activated], not
 *   as an action.
 * @property label Human-readable button text shown to the user.
 */
public data class NotificationAction(
    val id: String,
    val label: String,
) {
    init {
        require(id.isNotBlank()) { "action id must be non-blank" }
        require(id != DEFAULT_ID) {
            "'$DEFAULT_ID' is reserved for the whole-notification click; it arrives as NotificationEvent.Activated"
        }
        require(label.isNotBlank()) { "action label must be non-blank" }
    }

    public companion object {
        /**
         * The freedesktop action key for "the notification body itself was
         * clicked". The library registers it transparently so a plain click
         * surfaces as [NotificationEvent.Activated]; consumers never construct
         * an action with this id.
         */
        internal const val DEFAULT_ID: String = "default"
    }
}
