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
 * This app has shipped two defaults over its life, and both are addresses the
 * carrier resets mid-handshake: `dsh.owen1.de5.net:19092` up to v0.2.0, then
 * `mcp.owen1.de5.net` from v0.3.0. Each was written into storage on first run,
 * so an install holding either would faithfully dial a dead address after an
 * upgrade — reproducing the exact failure the migration exists to fix. A stored
 * copy of any retired default — or no stored value at all — therefore resolves
 * to the current default; anything the operator typed deliberately is left
 * alone.
 *
 * The comparison is deliberately forgiving about the two things people actually
 * get wrong when typing a URL by hand: trailing slashes and letter case. If it
 * were an exact string match, a stored `.../19092/` would survive the upgrade
 * and the operator would stay stranded on the address that never connects,
 * which is the one outcome this function exists to prevent. Nothing beyond
 * those two normalisations is forgiven — a genuinely different address is still
 * the operator's to keep.
 */
internal fun resolveRelayBase(stored: String?): String {
    val value = stored?.trim().orEmpty()
    if (value.isEmpty()) return Prefs.DEFAULT_RELAY_BASE
    if (isARetiredDefault(value)) return Prefs.DEFAULT_RELAY_BASE
    return value
}

/**
 * True when [value] points at an address this app previously shipped as its own
 * default, ignoring trailing slashes and case.
 */
private fun isARetiredDefault(value: String): Boolean =
    Prefs.RETIRED_RELAY_BASES.any { retired ->
        value.trimEnd('/').equals(retired.trimEnd('/'), ignoreCase = true)
    }
