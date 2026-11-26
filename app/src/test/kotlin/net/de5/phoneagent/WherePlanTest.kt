package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `where` (v0.8.0-pad12, SPEC.md §3.21).
 *
 * The JSON is parsed by the controller field by field, so both the key order and
 * the tolerance for the real `dumpsys window` tail matter.
 */
class WherePlanTest {

    @Test
    fun theJsonKeepsTheDocumentedKeyOrder() {
        assertEquals(
            "{\"ok\":true,\"pkg\":\"com.tencent.mm\",\"act\":\"ChattingMainUI\",\"scr\":\"on\",\"kg\":\"none\"}",
            WherePlan.json("com.tencent.mm", "ChattingMainUI", WherePlan.SCR_ON, "none")
        )
    }

    @Test
    fun anUnknownActivityIsAnEmptyStringNotTheWordNull() {
        assertEquals(
            "{\"ok\":true,\"pkg\":\"com.android.launcher\",\"act\":\"\",\"scr\":\"off\",\"kg\":\"locked\"}",
            WherePlan.json("com.android.launcher", "", WherePlan.SCR_OFF, "locked")
        )
    }

    @Test
    fun quotesAndBackslashesInAPackageNameAreEscaped() {
        val line = WherePlan.json("a\"b", "c\\d", WherePlan.SCR_ON, "none")
        assertEquals("{\"ok\":true,\"pkg\":\"a\\\"b\",\"act\":\"c\\\\d\",\"scr\":\"on\",\"kg\":\"none\"}", line)
    }

    @Test
    fun theRealFocusLineYieldsTheActivityClass() {
        val dump = "  mCurrentFocus=Window{e21ab69 u0 com.tencent.mm/" +
            "com.tencent.mm.ui.chatting.variants.ChattingMainUI}"
        assertEquals("com.tencent.mm.ui.chatting.variants.ChattingMainUI", WherePlan.activityOf(dump))
    }

    @Test
    fun theBrokenPipeTailAfterTheFocusLineIsTolerated() {
        val dump = "mCurrentFocus=Window{abc u0 com.android.settings/.Settings}\n" +
            "Failed to write while dumping service window: Broken pipe"
        assertEquals(".Settings", WherePlan.activityOf(dump))
    }

    @Test
    fun aNullFocusOrAFocusWithoutSlashYieldsAnEmptyString() {
        assertEquals("", WherePlan.activityOf("mCurrentFocus=null"))
        assertEquals("", WherePlan.activityOf("mCurrentFocus=Window{abc u0 com.android.launcher3}"))
        assertEquals("", WherePlan.activityOf("nothing interesting here"))
    }

    @Test
    fun theShellCommandIsTheDocumentedOne() {
        assertEquals("dumpsys window | grep mCurrentFocus", WherePlan.FOCUS_COMMAND)
        assertEquals("usage: where", WherePlan.USAGE)
    }

    @Test
    fun theNoAccessibilityLineNamesTheServiceInsteadOfJustFailing() {
        val text = WherePlan.noAccessibility()
        assertEquals(true, text.contains("无障碍"))
        assertEquals(true, text.contains("phoneagent"))
    }
}
