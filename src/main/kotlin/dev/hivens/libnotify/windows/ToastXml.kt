package dev.hivens.libnotify.windows

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.Timeout
import dev.hivens.libnotify.Urgency

/**
 * Builds the `ToastGeneric` XML payload a [Notification] turns into. Pure
 * string assembly with no native dependency, so it is exhaustively
 * unit-testable -- the part of the Windows backend most prone to silent
 * escaping bugs is the part that needs no Windows to test.
 *
 * The `launch` attribute and each `<action arguments>` carry the routing key
 * the activation handler reads back: a body click activates with the reserved
 * [NotificationAction.DEFAULT_ID]; an action button activates with that
 * action's id.
 */
internal object ToastXml {

    /** Windows toast caps actions at five; extras would make the shell reject the XML. */
    const val MAX_ACTIONS: Int = 5

    /**
     * @param imageUri an absolute `file:///` URI for an appLogoOverride image,
     *   or null for no image (the Windows shell reads images from a path/URI,
     *   not inline bytes).
     */
    fun build(n: Notification, imageUri: String?): String {
        val sb = StringBuilder(256)
        sb.append("<toast")
        scenarioFor(n.urgency)?.let { sb.append(" scenario=\"").append(it).append('"') }
        durationFor(n.timeout)?.let { sb.append(" duration=\"").append(it).append('"') }
        sb.append(" launch=\"").append(escape(NotificationAction.DEFAULT_ID)).append('"')
        sb.append(" activationType=\"foreground\">")

        sb.append("<visual><binding template=\"ToastGeneric\">")
        sb.append("<text>").append(escape(n.title)).append("</text>")
        if (n.body.isNotEmpty()) {
            sb.append("<text>").append(escape(n.body)).append("</text>")
        }
        if (imageUri != null) {
            sb.append("<image placement=\"appLogoOverride\" src=\"").append(escape(imageUri)).append("\"/>")
        }
        sb.append("</binding></visual>")

        val actions = n.actions.take(MAX_ACTIONS)
        if (actions.isNotEmpty()) {
            sb.append("<actions>")
            for (a in actions) {
                sb.append("<action content=\"").append(escape(a.label))
                    .append("\" arguments=\"").append(escape(a.id))
                    .append("\" activationType=\"foreground\"/>")
            }
            sb.append("</actions>")
        }

        sb.append("</toast>")
        return sb.toString()
    }

    /** Critical urgency raises the toast above focus assist with a sound; others use the default. */
    fun scenarioFor(urgency: Urgency): String? = if (urgency == Urgency.CRITICAL) "urgent" else null

    /**
     * Toasts have only two durations. Map [Timeout.Never] and any timeout at or
     * past the short/long boundary (~25s) to "long"; shorter values to "short";
     * the server default omits the attribute.
     */
    fun durationFor(timeout: Timeout): String? = when (timeout) {
        is Timeout.ServerDefault -> null
        is Timeout.Never -> "long"
        is Timeout.After -> if (timeout.millis < 25_000) "short" else "long"
    }

    /** XML-escape text and attribute values: the five predefined entities. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
