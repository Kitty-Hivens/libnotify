package dev.hivens.libnotify

/**
 * Application identity for a [Notifier], fixed for its lifetime.
 *
 * @property appName Human-readable application name. freedesktop sends it as
 *   the `app_name` (servers show it as the source); on Windows it is the
 *   fallback display name; macOS derives the source from the process bundle and
 *   ignores it. Must be non-blank.
 * @property appId Windows Application User Model ID (AUMID), e.g.
 *   `"Kitty-Hivens.MyApp"`. **Required for toasts to appear on Windows** -- the
 *   OS routes and de-duplicates toasts by AUMID and silently drops toasts whose
 *   AUMID is not registered (an installed Start-menu shortcut carrying the
 *   AUMID, or a registered COM server, establishes it). When null, the Windows
 *   backend tries the process's current AUMID and logs a warning if it cannot
 *   resolve one. Ignored on Linux and macOS.
 * @property defaultIconName Optional freedesktop themed icon name used as the
 *   `app_icon` when a [Notification] supplies neither
 *   [Notification.iconBytes] nor [Notification.iconName]. Ignored off Linux.
 * @property defaultIconBytes Optional encoded image (PNG) used as the fallback
 *   per-notification image when a [Notification] carries no icon of its own.
 *   If set, must be non-empty.
 */
public data class NotifierConfig(
    val appName: String,
    val appId: String? = null,
    val defaultIconName: String? = null,
    val defaultIconBytes: ByteArray? = null,
) {
    init {
        require(appName.isNotBlank()) { "appName must be non-blank" }
        require(defaultIconBytes == null || defaultIconBytes.isNotEmpty()) {
            "defaultIconBytes, when set, must be non-empty"
        }
    }

    // Content-compare the byte array (the TrayBuilder pattern); the generated
    // equals/hashCode would use array identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotifierConfig) return false
        return appName == other.appName &&
            appId == other.appId &&
            defaultIconName == other.defaultIconName &&
            (defaultIconBytes?.contentEquals(other.defaultIconBytes) ?: (other.defaultIconBytes == null))
    }

    override fun hashCode(): Int {
        var result = appName.hashCode()
        result = 31 * result + (appId?.hashCode() ?: 0)
        result = 31 * result + (defaultIconName?.hashCode() ?: 0)
        result = 31 * result + (defaultIconBytes?.contentHashCode() ?: 0)
        return result
    }
}
