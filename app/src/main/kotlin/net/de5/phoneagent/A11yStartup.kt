package net.de5.phoneagent

/**
 * The accessibility self-check that runs when **our own process starts**
 * (SPEC.md §7).
 *
 * v0.4.3 hung the post-update rebuild on `ACTION_MY_PACKAGE_REPLACED`. On the
 * Meizu (Flyme AIOS 12.7 / Android 16) that broadcast is never delivered to our
 * receiver: a clean install of v0.4.3 with the log cleared produced not one
 * `[boot]` line, while the heartbeat's `自愈:` line appeared 15 s later — the
 * repair only happened because the 5-minute heartbeat window came around.
 *
 * The engineering answer is **not** to fight the ROM's autostart whitelist: that
 * is a system setting we cannot change and should not change. The answer is to
 * hang the check on something the platform cannot skip — our own process
 * starting. The process always starts (without it the agent does not work at
 * all); a broadcast may or may not arrive.
 *
 * Pure so the four states can be asserted without a Context: the caller reads
 * `instance != null`, `isAccessibilityListed()` and `canWriteSecure()`.
 */
internal object A11yStartup {

    sealed class Plan {
        /** Both facts healthy: say nothing, write nothing. */
        object Nothing : Plan()

        /** Entry missing: append it (keeps every other accessibility service). */
        object Heal : Plan()

        /** Entry listed but nothing bound — the state an update leaves behind. */
        object Rescue : Plan()

        /** Broken but unrepairable from inside the app: log the skip once. */
        object SkipNoPermission : Plan()
    }

    fun plan(live: Boolean, listed: Boolean, canWrite: Boolean): Plan = when {
        live && listed -> Plan.Nothing
        !canWrite -> Plan.SkipNoPermission
        !listed -> Plan.Heal
        else -> Plan.Rescue
    }
}
