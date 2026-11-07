package dev.hivens.libnotify

/**
 * Application identity for a [Notifier], fixed for its lifetime.
 *
 * Kotlin builds one with named arguments, Java with [Builder]:
 * `new NotifierConfig.Builder("My App").appId("com.example.MyApp").build()`.
 *
 * @property appName Human-readable application name. freedesktop sends it as
 *   the `app_name` (servers show it as the source), on Windows it is the
 *   fallback display name, macOS derives the source from the process bundle and
 *   ignores it. Must be non-blank.
 * @property appId Optional stable application id. Windows needs it to show toasts
 *   at all: the shell routes them by AUMID and silently drops those whose AUMID is
 *   not registered (an installed Start-menu shortcut carrying it, or a registered
 *   COM server). When null, the Windows backend falls back to [appName] and logs a
 *   warning. Linux sends it as the `desktop-entry` hint, which servers use to group
 *   the notifications and find the app's icon and name, so it should match the
 *   name of the app's `.desktop` file without the extension. A reverse-DNS id such
 *   as `"com.example.MyApp"` is valid as both. Ignored on macOS.
 * @property defaultIconName Optional freedesktop themed icon name used as the
 *   `app_icon` when a [Notification] supplies neither
 *   [Notification.iconBytes] nor [Notification.iconName]. Ignored off Linux.
 * @property defaultIconBytes Optional encoded image (PNG) used as the fallback
 *   per-notification image when a [Notification] carries no icon of its own.
 *   If set, must be non-empty.
 */
// Not a data class, for the reason given on Notification.
public class NotifierConfig(
    public val appName: String,
    public val appId: String? = null,
    public val defaultIconName: String? = null,
    public val defaultIconBytes: ByteArray? = null,
) {
    init {
        require(appName.isNotBlank()) { "appName must be non-blank" }
        require(defaultIconBytes == null || defaultIconBytes.isNotEmpty()) {
            "defaultIconBytes, when set, must be non-empty"
        }
    }

    /** A [Builder] holding this config's values. */
    public fun toBuilder(): Builder = Builder(appName)
        .appId(appId)
        .defaultIconName(defaultIconName)
        .defaultIconBytes(defaultIconBytes)

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

    override fun toString(): String =
        "NotifierConfig(appName=$appName, appId=$appId, defaultIconName=$defaultIconName, " +
            "defaultIconBytes=${defaultIconBytes?.let { "${it.size} bytes" }})"

    /** Builds a [NotifierConfig] step by step. [build] checks it like the constructor does. */
    public class Builder(private var appName: String) {
        private var appId: String? = null
        private var defaultIconName: String? = null
        private var defaultIconBytes: ByteArray? = null

        public fun appName(value: String): Builder = apply { appName = value }
        public fun appId(value: String?): Builder = apply { appId = value }
        public fun defaultIconName(value: String?): Builder = apply { defaultIconName = value }
        public fun defaultIconBytes(value: ByteArray?): Builder = apply { defaultIconBytes = value }

        public fun build(): NotifierConfig = NotifierConfig(appName, appId, defaultIconName, defaultIconBytes)
    }
}
