package dev.hivens.libnotify

/**
 * What the live notification backend can actually do, so a consumer can adapt
 * before posting rather than discover limits by trial.
 *
 * On Linux these are read from the running notification server's
 * `GetCapabilities` reply: the same daemon (Dunst, Mako, Plasma, GNOME Shell)
 * varies in what it honours. On Windows and macOS they are fixed properties of
 * the chosen API.
 *
 * Reporting a capability as false does not make posting fail: the backend drops
 * the unsupported facet and shows the rest. The flags exist so a consumer can,
 * say, fold a second action into the body text when only one action is
 * available, rather than silently losing it.
 *
 * A consumer only reads these from [Notifier.capabilities], except to build a
 * [Notifier] test double, which is what [Builder] is for.
 *
 * @property actions Whether action buttons render at all.
 * @property maxActions How many actions survive. 0 when [actions] is false,
 *   [Int.MAX_VALUE] when the server advertises no fixed ceiling (the consumer
 *   stays responsible for not flooding the banner).
 * @property bodyMarkup Whether the server interprets a small HTML-ish subset in
 *   the body (freedesktop `body-markup`). Informational: the library escapes
 *   [Notification.body] for such a server, so no markup reaches it. False when
 *   no server answered at create time, even if one that parses markup starts
 *   later, and the escaping still follows that later server.
 * @property icons Whether a per-notification image ([Notification.iconBytes] /
 *   [Notification.iconName]) is shown.
 * @property replace Whether posting with a live [Notification.tag] replaces the
 *   prior banner in place (versus stacking a second one).
 * @property closeEvents Whether [NotificationEvent.Dismissed] is emitted for
 *   dismissals the library did not cause, a timeout or the user. The one after
 *   [Notifier.cancel] is emitted regardless.
 * @property urgency Whether [Urgency] changes presentation.
 */
// Not a data class and no public constructor: a new capability is then a new
// property plus a Builder method, and breaks no caller compiled against an
// older version.
public class NotifierCapabilities internal constructor(
    public val actions: Boolean,
    public val maxActions: Int,
    public val bodyMarkup: Boolean,
    public val icons: Boolean,
    public val replace: Boolean,
    public val closeEvents: Boolean,
    public val urgency: Boolean,
) {
    init {
        require(maxActions >= 0) { "maxActions must be >= 0, was $maxActions" }
    }

    /** A [Builder] holding these values. */
    public fun toBuilder(): Builder = Builder()
        .actions(actions)
        .maxActions(maxActions)
        .bodyMarkup(bodyMarkup)
        .icons(icons)
        .replace(replace)
        .closeEvents(closeEvents)
        .urgency(urgency)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotifierCapabilities) return false
        return actions == other.actions &&
            maxActions == other.maxActions &&
            bodyMarkup == other.bodyMarkup &&
            icons == other.icons &&
            replace == other.replace &&
            closeEvents == other.closeEvents &&
            urgency == other.urgency
    }

    override fun hashCode(): Int {
        var result = actions.hashCode()
        result = 31 * result + maxActions
        result = 31 * result + bodyMarkup.hashCode()
        result = 31 * result + icons.hashCode()
        result = 31 * result + replace.hashCode()
        result = 31 * result + closeEvents.hashCode()
        result = 31 * result + urgency.hashCode()
        return result
    }

    override fun toString(): String =
        "NotifierCapabilities(actions=$actions, maxActions=$maxActions, bodyMarkup=$bodyMarkup, " +
            "icons=$icons, replace=$replace, closeEvents=$closeEvents, urgency=$urgency)"

    /**
     * Builds [NotifierCapabilities] for a [Notifier] test double. Everything starts
     * as unsupported, false and zero, so a double claims only what it sets.
     */
    public class Builder {
        private var actions = false
        private var maxActions = 0
        private var bodyMarkup = false
        private var icons = false
        private var replace = false
        private var closeEvents = false
        private var urgency = false

        public fun actions(value: Boolean): Builder = apply { actions = value }
        public fun maxActions(value: Int): Builder = apply { maxActions = value }
        public fun bodyMarkup(value: Boolean): Builder = apply { bodyMarkup = value }
        public fun icons(value: Boolean): Builder = apply { icons = value }
        public fun replace(value: Boolean): Builder = apply { replace = value }
        public fun closeEvents(value: Boolean): Builder = apply { closeEvents = value }
        public fun urgency(value: Boolean): Builder = apply { urgency = value }

        public fun build(): NotifierCapabilities =
            NotifierCapabilities(actions, maxActions, bodyMarkup, icons, replace, closeEvents, urgency)
    }
}
