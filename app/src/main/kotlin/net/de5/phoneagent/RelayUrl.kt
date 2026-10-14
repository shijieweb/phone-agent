package net.de5.phoneagent

/**
 * Relay base-URL handling.
 *
 * The operator types a base URL such as `https://dsh.owen1.de5.net:19092`, but
 * trailing slashes are easy to add by accident and a naive concatenation then
 * produces `https://host//agent/hello`, which nginx resolves as a different
 * path. Every relay path therefore goes through [joinUrl].
 */
internal fun joinUrlOrNull(base: String?, path: String): String? {
    val b = base?.trim().orEmpty()
    if (b.isEmpty()) return null
    val p = path.trim()
    if (p.isEmpty()) return null
    return b.trimEnd('/') + "/" + p.trimStart('/')
}

internal fun joinUrl(base: String?, path: String): String =
    joinUrlOrNull(base, path) ?: error("relay base url is not configured")

/**
 * Which relay address an install should actually use, given whatever is in its
 * settings.
 *
 * Up to v0.2.0 the app shipped [Prefs.LEGACY_RELAY_BASE] as its default and
 * wrote that value into storage on first run. That address is the one the
 * carrier resets mid-handshake, so leaving it in place after an upgrade would
 * reproduce the exact failure we are fixing. A stored copy of the legacy
 * default — or no stored value at all — therefore resolves to the current
 * default; anything the operator typed deliberately is left alone.
 */
internal fun resolveRelayBase(stored: String?): String {
    val value = stored?.trim().orEmpty()
    if (value.isEmpty()) return Prefs.DEFAULT_RELAY_BASE
    if (value == Prefs.LEGACY_RELAY_BASE) return Prefs.DEFAULT_RELAY_BASE
    return stored!!.trim()
}
