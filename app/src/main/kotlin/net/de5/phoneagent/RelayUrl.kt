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
