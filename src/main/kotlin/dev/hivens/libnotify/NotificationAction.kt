package dev.hivens.libnotify

/**
 * The freedesktop action key for "the notification body itself was clicked".
 * The library registers it on every post, so a plain click surfaces as
 * [NotificationEvent.Activated], and no [NotificationAction] may use it.
 */
internal const val DEFAULT_ACTION_ID: String = "default"

/**
 * A button offered on a [Notification]. Clicking it fires
 * [NotificationEvent.ActionInvoked] carrying this action's [id].
 *
 * Backend coverage of actions varies, read [NotifierCapabilities.actions]
 * and [NotifierCapabilities.maxActions]. Linux freedesktop supports an
 * unbounded list (servers cap visually), Windows toast allows up to five, and
 * macOS `NSUserNotification` shows the first action as a button and the rest in
 * its alternate-action menu.
 *
 * @property id Stable identifier surfaced in [NotificationEvent.ActionInvoked].
 *   Must be non-blank and must not be `"default"`: the whole-notification click
 *   is delivered as [NotificationEvent.Activated], not as an action.
 * @property label Human-readable button text shown to the user.
 */
public data class NotificationAction(
    val id: String,
    val label: String,
) {
    init {
        require(id.isNotBlank()) { "action id must be non-blank" }
        require(id != DEFAULT_ACTION_ID) {
            "'$DEFAULT_ACTION_ID' is reserved for the whole-notification click, it arrives as NotificationEvent.Activated"
        }
        require(label.isNotBlank()) { "action label must be non-blank" }
    }
}
