package dev.hivens.libnotify

/**
 * One desktop notification to post via [Notifier.notify].
 *
 * Immutable value: to change a shown notification, build a new one with the
 * same [tag] and post it again -- the backend replaces the prior banner in
 * place where the platform supports it (freedesktop `replaces_id`, Windows
 * toast `Tag`, macOS `identifier`).
 *
 * Cross-platform by construction: the model carries only what all three
 * backends can express. Per-platform ceilings (max actions, whether a
 * per-notification image renders, whether urgency is honoured) are reported
 * by [Notifier.capabilities], and a backend silently drops what it cannot do
 * rather than failing the post -- a notification with five actions still
 * shows its text on a backend that allows one.
 *
 * @property title The bold headline. freedesktop calls this the "summary".
 *   Must be non-blank.
 * @property body Secondary text under the title. Plain text; the library does
 *   not interpret markup even where a backend advertises `body-markup`
 *   ([NotifierCapabilities.bodyMarkup] only reports the capability).
 * @property iconBytes Optional per-notification image as encoded bytes (PNG is
 *   universal). Decoded by the library on backends that take raw pixels
 *   (freedesktop `image-data` hint); see [NotifierCapabilities.icons]. If set,
 *   must be non-empty.
 * @property iconName Optional freedesktop themed icon name (e.g.
 *   `"dialog-information"`). Used as the `app_icon` on Linux; ignored where the
 *   platform has no icon-theme concept. When both [iconBytes] and [iconName]
 *   are set, a backend prefers whichever it can render, [iconBytes] first.
 * @property urgency Hint at how insistently to present the notification. Fully
 *   honoured on Linux; mapped to a best-effort scenario on Windows; ignored on
 *   macOS. See [Urgency].
 * @property timeout How long the banner stays before auto-dismissing. See
 *   [Timeout]; honoured only where [NotifierCapabilities] allows.
 * @property actions Buttons offered on the notification, in order. Clicking one
 *   fires [NotificationEvent.ActionInvoked]; ids must be unique and must not be
 *   the reserved [NotificationAction.DEFAULT_ID]. A backend truncates to its
 *   [NotifierCapabilities.maxActions] ceiling.
 * @property category Optional freedesktop category key (e.g. `"im.received"`).
 *   Sent as the `category` hint on Linux; ignored elsewhere.
 * @property tag Optional stable key for replace-in-place and for the id carried
 *   by every [NotificationEvent] this notification raises. Posting a new
 *   notification with a tag already live replaces the old banner. When null, the
 *   library assigns a fresh unique id per post (no replacement).
 */
public data class Notification(
    val title: String,
    val body: String = "",
    val iconBytes: ByteArray? = null,
    val iconName: String? = null,
    val urgency: Urgency = Urgency.NORMAL,
    val timeout: Timeout = Timeout.ServerDefault,
    val actions: List<NotificationAction> = emptyList(),
    val category: String? = null,
    val tag: String? = null,
) {
    init {
        require(title.isNotBlank()) { "title must be non-blank" }
        require(iconBytes == null || iconBytes.isNotEmpty()) { "iconBytes, when set, must be non-empty" }
        require(tag == null || tag.isNotBlank()) { "tag, when set, must be non-blank" }
        val ids = actions.map { it.id }
        require(ids.toSet().size == ids.size) { "action ids must be unique within a notification: $ids" }
    }

    // Generated equals/hashCode would compare iconBytes by identity, so two
    // notifications with byte-identical icons would not compare equal.
    // Override to content-compare the array (the TrayBuilder pattern).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Notification) return false
        return title == other.title &&
            body == other.body &&
            (iconBytes?.contentEquals(other.iconBytes) ?: (other.iconBytes == null)) &&
            iconName == other.iconName &&
            urgency == other.urgency &&
            timeout == other.timeout &&
            actions == other.actions &&
            category == other.category &&
            tag == other.tag
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + (iconBytes?.contentHashCode() ?: 0)
        result = 31 * result + (iconName?.hashCode() ?: 0)
        result = 31 * result + urgency.hashCode()
        result = 31 * result + timeout.hashCode()
        result = 31 * result + actions.hashCode()
        result = 31 * result + (category?.hashCode() ?: 0)
        result = 31 * result + (tag?.hashCode() ?: 0)
        return result
    }
}

/**
 * How insistently to present a notification.
 *
 * - Linux: the `urgency` hint, byte 0/1/2 -- servers darken/pin [CRITICAL].
 * - Windows: mapped to a toast scenario; [CRITICAL] uses `scenario="urgent"`.
 * - macOS: `NSUserNotification` has no urgency concept; ignored.
 */
public enum class Urgency {
    LOW,
    NORMAL,
    CRITICAL,
}

/**
 * How long a notification banner stays before auto-dismissing.
 *
 * Backends honour this only as far as the platform allows: freedesktop takes
 * an exact millisecond value, Windows toast quantises to short/long, macOS
 * ignores it entirely (the system owns banner lifetime).
 */
public sealed interface Timeout {

    /** Let the notification server pick the duration. The usual default. */
    public data object ServerDefault : Timeout

    /**
     * Stay until the user dismisses it (no auto-expire). freedesktop
     * `expire_timeout = 0`; Windows toast long-duration scenario. Use for
     * notifications the user must act on.
     */
    public data object Never : Timeout

    /**
     * Auto-dismiss after [millis] milliseconds. Quantised by backends that
     * cannot honour an exact value (Windows: <25s short, else long).
     */
    public data class After(val millis: Int) : Timeout {
        init {
            require(millis > 0) {
                "After.millis must be > 0; use Never for a sticky notification or ServerDefault to let the server choose"
            }
        }
    }
}
