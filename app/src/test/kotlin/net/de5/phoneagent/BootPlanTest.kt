package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boot/update broadcast order (SPEC.md §7).
 *
 * The regression this file pins: v0.4.2 returned early when auto-start was off,
 * so an operator who had pressed 停止 got an update with a dead accessibility
 * binding and no rebuild — the phone then answered every command with
 * "accessibility service not connected".
 */
class BootPlanTest {

    @Test
    fun theRebuildHappensEvenWhenAutoStartIsOff() {
        val steps = BootPlan.steps(
            BootPlan.ACTION_MY_PACKAGE_REPLACED,
            stayOn = false,
            autoStart = false,
            configured = true
        )
        assertTrue(steps.toString(), steps.contains(BootPlan.Step.RebuildAccessibility))
        // "Stop" means "do not run the relay poller", not "stay broken".
        assertFalse(steps.toString(), steps.contains(BootPlan.Step.StartService))
    }

    @Test
    fun theRebuildAlsoHappensWhenTheRelayIsNotConfigured() {
        val steps = BootPlan.steps(
            BootPlan.ACTION_MY_PACKAGE_REPLACED,
            stayOn = false,
            autoStart = false,
            configured = false
        )
        assertTrue(steps.toString(), steps.contains(BootPlan.Step.RebuildAccessibility))
    }

    @Test
    fun theAdbPortIsRediscoveredRegardlessOfAutoStart() {
        for (autoStart in listOf(true, false)) {
            val steps = BootPlan.steps(
                BootPlan.ACTION_MY_PACKAGE_REPLACED,
                stayOn = false,
                autoStart = autoStart,
                configured = true
            )
            assertTrue(steps.toString(), steps.contains(BootPlan.Step.DiscoverAdbPort))
        }
    }

    @Test
    fun aPlainBootDoesNotRewriteTheAccessibilitySetting() {
        // On boot Android restores the entry itself; writing it then would
        // silently override an operator who turned accessibility off on purpose.
        val steps = BootPlan.steps(
            "android.intent.action.BOOT_COMPLETED",
            stayOn = false,
            autoStart = true,
            configured = true
        )
        assertFalse(steps.toString(), steps.contains(BootPlan.Step.RebuildAccessibility))
        assertTrue(steps.toString(), steps.contains(BootPlan.Step.StartService))
    }

    @Test
    fun thePanelTimeoutIsRestoredOnlyWhenStayOnIsSet() {
        val on = BootPlan.steps("android.intent.action.BOOT_COMPLETED", true, true, true)
        assertTrue(on.toString(), on.contains(BootPlan.Step.RestoreStayOn))
        val off = BootPlan.steps("android.intent.action.BOOT_COMPLETED", false, true, true)
        assertFalse(off.toString(), off.contains(BootPlan.Step.RestoreStayOn))
    }

    @Test
    fun theServiceStartIsTheOnlyStepAutoStartGates() {
        val running = BootPlan.steps(BootPlan.ACTION_MY_PACKAGE_REPLACED, true, true, true)
        val stopped = BootPlan.steps(BootPlan.ACTION_MY_PACKAGE_REPLACED, true, false, true)
        assertEquals(
            running.filter { it != BootPlan.Step.StartService },
            stopped.filter { it != BootPlan.Step.StartService }
        )
        assertTrue(running.contains(BootPlan.Step.StartService))
        assertFalse(stopped.contains(BootPlan.Step.StartService))
    }

    @Test
    fun anUnconfiguredAgentStartsNothing() {
        // shouldAutoStart is shared with the start button, so this also proves the
        // two can never disagree.
        val steps = BootPlan.steps(BootPlan.ACTION_MY_PACKAGE_REPLACED, false, true, false)
        assertFalse(steps.toString(), steps.contains(BootPlan.Step.StartService))
    }

    @Test
    fun theStartComesLastSoNothingBlocksIt() {
        val steps = BootPlan.steps(BootPlan.ACTION_MY_PACKAGE_REPLACED, true, true, true)
        assertEquals(BootPlan.Step.StartService, steps.last())
        assertEquals(
            listOf(
                BootPlan.Step.RestoreStayOn,
                BootPlan.Step.RebuildAccessibility,
                BootPlan.Step.DiscoverAdbPort,
                BootPlan.Step.StartService
            ),
            steps
        )
    }

    @Test
    fun theSiblingPackageReplacedBroadcastAlsoRebuilds() {
        // MY_PACKAGE_REPLACED and PACKAGE_REPLACED are delivered inconsistently
        // across ROMs; either one must lead to a rebuild.
        assertTrue(
            BootPlan.steps(BootPlan.ACTION_PACKAGE_REPLACED, false, false, true)
                .contains(BootPlan.Step.RebuildAccessibility)
        )
    }

    @Test
    fun aPlainBootNeverRebuilds() {
        assertTrue(
            BootPlan.steps("android.intent.action.BOOT_COMPLETED", false, true, true)
                .none { it is BootPlan.Step.RebuildAccessibility }
        )
    }
}
