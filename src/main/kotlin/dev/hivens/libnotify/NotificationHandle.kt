package dev.hivens.libnotify

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * A reference to a posted notification, returned by [Notifier.notify].
 *
 * Carries the [id] that every [NotificationEvent] for this notification
 * reports, and that [Notifier.cancel] takes to dismiss it. When the posting
 * [Notification] supplied a [Notification.tag], [id] equals that tag (so a
 * later post with the same tag replaces this one). Otherwise the library
 * assigned an id unique to that notifier, prefixed with a random part, so it
 * matches no tag a caller would pick, no other notifier's id, and no id from an
 * earlier run of the process.
 *
 * A handle belongs to the notifier that issued it: [Notifier.cancel] on another
 * notifier returns false, even when the ids are the same. It holds no backend
 * reference, so keeping one around cannot pin native resources.
 */
// Not a data class, for the reason given on Notification.
public class NotificationHandle internal constructor(
    public val id: String,
    internal val issuer: HandleIssuer?,
) {
    /**
     * A handle no notifier issued, for a [Notifier] test double to return. A real
     * notifier refuses to cancel it.
     */
    public constructor(id: String) : this(id, null)

    init {
        require(id.isNotBlank()) { "id must be non-blank" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is NotificationHandle && id == other.id && issuer === other.issuer)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "NotificationHandle(id=$id)"
}

/** Mints the generated ids and the handles of one notifier, and recognises its own. */
internal class HandleIssuer {
    // 48 random bits: a different prefix per notifier and per run, short enough
    // for a Windows toast tag, which allows 64 characters.
    private val prefix = "ln-" + UUID.randomUUID().toString().replace("-", "").take(12) + "-"
    private val counter = AtomicLong()

    fun nextId(): String = prefix + counter.getAndIncrement()

    fun handle(id: String): NotificationHandle = NotificationHandle(id, this)

    fun issued(handle: NotificationHandle): Boolean = handle.issuer === this
}
