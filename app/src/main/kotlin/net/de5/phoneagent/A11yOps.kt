package net.de5.phoneagent

/**
 * The accessibility repair sequence (SPEC.md section 3.15).
 *
 * Kept pure — a tiny [SecureSink] instead of a ContentResolver — because the
 * sequence itself is the fragile part: the order of the four writes, the two
 * seconds in the middle, and the fact that the list is **deleted** rather than
 * overwritten with an empty string. On the Meizu, `settings put secure
 * enabled_accessibility_services ""` answers `Bad arguments` and changes
 * nothing, so a "clear then write back" that clears with a blank value is a
 * no-op dressed up as a repair.
 *
 * The same sequence was verified by hand on the phone:
 *
 * ```
 * settings put secure accessibility_enabled 0
 * settings delete secure enabled_accessibility_services
 * (wait ~2 s)
 * settings put secure enabled_accessibility_services <component>
 * settings put secure accessibility_enabled 1
 * (wait ~3 s, the service binds)
 * ```
 */
internal interface SecureSink {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun getInt(key: String, def: Int): Int

    /** Must be a real delete: writing a blank string is rejected by the framework. */
    fun delete(key: String)
}

internal object A11yOps {

    /** Our accessibility component, in the flat form the settings store expects. */
    const val COMPONENT = "net.de5.phoneagent/net.de5.phoneagent.PhoneAccessibilityService"

    const val KEY_SERVICES = "enabled_accessibility_services"
    const val KEY_ENABLED = "accessibility_enabled"

    /**
     * How long the system needs to notice that the binding was released.
     *
     * Writing the entry back in the same millisecond leaves the old binding in
     * place: the framework compares the setting, sees the same value it already
     * had, and does nothing. Measured on the Meizu: 2 s is enough, and the
     * service is bound again ~3 s after the write.
     */
    const val RESCUE_SLEEP_MS = 2_000L

    const val ACTION_HEAL = "heal"
    const val ACTION_RESCUE = "rescue"
    const val ACTION_NONE = "none"

    /**
     * Which repair fits the state that was observed.
     *
     * `heal` appends a missing entry; it cannot help when the entry is already
     * there and the binding is dead — that is exactly the v0.4.2 failure, where
     * `a11y heal` answered "条目已存在" and changed nothing.
     */
    fun repairAction(listed: Boolean, live: Boolean): String = when {
        !listed -> ACTION_HEAL
        !live -> ACTION_RESCUE
        else -> ACTION_NONE
    }

    /** Every entry except ours, verbatim, in their original order. */
    fun others(current: String?, component: String = COMPONENT): List<String> =
        (current ?: "")
            .split(':')
            .filter { it.isNotBlank() }
            .filterNot { it.equals(component, ignoreCase = true) }

    /** `others` plus us, or just us when we are the only entry. */
    fun joined(entries: List<String>, component: String = COMPONENT): String =
        if (entries.isEmpty()) component else entries.joinToString(":") + ":" + component

    /**
     * Force a dead binding to be re-created. Blocking for [RESCUE_SLEEP_MS], so
     * the caller must be off the main thread.
     *
     * @param sleep injected so the unit test does not actually wait two seconds.
     */
    fun reconnect(
        sink: SecureSink,
        sleep: (Long) -> Unit,
        component: String = COMPONENT
    ): String {
        val entries = others(sink.getString(KEY_SERVICES), component)
        sink.putInt(KEY_ENABLED, 0)
        // delete, never putString(key, ""): the framework refuses the blank value
        // with "Bad arguments" and the binding is never released.
        sink.delete(KEY_SERVICES)
        sleep(RESCUE_SLEEP_MS)
        sink.putString(KEY_SERVICES, joined(entries, component))
        sink.putInt(KEY_ENABLED, 1)
        return "已强制重连无障碍服务（保留原有 " + entries.size + " 条）"
    }
}
