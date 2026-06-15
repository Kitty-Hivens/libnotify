package dev.hivens.libnotify

/**
 * What the live notification backend can actually do, so a consumer can adapt
 * before posting rather than discover limits by trial.
 *
 * On Linux these are read from the running notification server's
 * `GetCapabilities` reply -- the same daemon (Dunst, Mako, Plasma, GNOME
 * Shell) varies in what it honours. On Windows and macOS they are fixed
 * properties of the chosen API.
 *
 * Reporting a capability as false does not make posting fail: the backend
 * drops the unsupported facet and shows the rest. The flags exist so a
 * consumer can, say, fold a second action into the body text when only one
 * action is available, rather than silently losing it.
 *
 * @property actions Whether action buttons render at all.
 * @property maxActions How many actions survive. 0 when [actions] is false;
 *   [Int.MAX_VALUE] when the server advertises no fixed ceiling (the consumer
 *   stays responsible for not flooding the banner).
 * @property bodyMarkup Whether the server interprets a small HTML-ish subset in
 *   the body (freedesktop `body-markup`). The library still sends body text
 *   verbatim; this only reports the server's stance.
 * @property icons Whether a per-notification image ([Notification.iconBytes] /
 *   [Notification.iconName]) is shown.
 * @property replace Whether posting with a live [Notification.tag] replaces the
 *   prior banner in place (versus stacking a second one).
 * @property closeEvents Whether [NotificationEvent.Dismissed] is emitted.
 * @property urgency Whether [Urgency] changes presentation.
 */
public data class NotifierCapabilities(
    val actions: Boolean,
    val maxActions: Int,
    val bodyMarkup: Boolean,
    val icons: Boolean,
    val replace: Boolean,
    val closeEvents: Boolean,
    val urgency: Boolean,
)
