package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-start accessibility check (SPEC.md §7).
 *
 * v0.4.3 hung this repair on `MY_PACKAGE_REPLACED`; Flyme never delivered it, so
 * the repair only happened when the 5-minute heartbeat came around. The rule
 * below is what now runs on every process start, and each of the four states
 * must be distinguishable — collapsing them is how a "skipped, no permission"
 * gets mistaken for "repaired".
 */
class A11yStartupTest {

    @Test
    fun aHealthyPhoneIsLeftAlone() {
        // Writing secure settings wakes every accessibility client on the phone;
        // doing it when nothing is wrong is its own outage.
        assertEquals(A11yStartup.Plan.Nothing, A11yStartup.plan(true, true, true))
    }

    @Test
    fun aMissingEntryIsAppended() {
        assertEquals(A11yStartup.Plan.Heal, A11yStartup.plan(false, false, true))
    }

    @Test
    fun aListedButUnboundServiceIsRescued() {
        // The exact state an update leaves behind: the entry survives, the
        // binding does not. Appending an existing entry does nothing here.
        assertEquals(A11yStartup.Plan.Rescue, A11yStartup.plan(false, true, true))
    }

    @Test
    fun withoutTheGrantNothingIsAttempted() {
        // A refusal retried on every start would only fill the log.
        assertEquals(A11yStartup.Plan.SkipNoPermission, A11yStartup.plan(false, true, false))
        assertEquals(A11yStartup.Plan.SkipNoPermission, A11yStartup.plan(false, false, false))
    }

    @Test
    fun aLiveBindingOutranksAMissingGrant() {
        // Nothing to repair, so the missing grant is irrelevant — reporting a skip
        // here would suggest the phone is broken when it is not.
        assertEquals(A11yStartup.Plan.Nothing, A11yStartup.plan(true, true, false))
    }

    @Test
    fun theTwoSourceMarkersAreDistinct() {
        // `安装后自愈` contains `自愈`, so the mapping must test the specific
        // spellings first or every install repair would be labelled 心跳.
        assertTrue(A11yHeal.SOURCE_INSTALL.contains(A11yHeal.SOURCE_STARTUP).not())
        assertEquals("装包", A11yPlan.sourceTag(A11yHeal.SOURCE_INSTALL + "(3000ms): x"))
        assertEquals("启动", A11yPlan.sourceTag(A11yHeal.SOURCE_STARTUP + ": x"))
        assertEquals("心跳", A11yPlan.sourceTag("自愈: x"))
        // A skip line from the install path still belongs to that path; only a
        // line from no known trigger has no tag.
        assertEquals("装包", A11yPlan.sourceTag("安装后自愈: 跳过，缺 WRITE_SECURE_SETTINGS"))
        assertEquals("", A11yPlan.sourceTag("常亮恢复: 已开启常亮"))
    }
}
