package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility repair sequence (SPEC.md §3.15).
 *
 * The load-bearing detail is the *delete*: on the Meizu,
 * `settings put secure enabled_accessibility_services ""` answers
 * `Bad arguments` and changes nothing, so a "clear then write back" that clears
 * with a blank string is a no-op dressed up as a repair. That is exactly how the
 * v0.4.2 rescue silently failed.
 */
class A11yOpsTest {

    /** Records every operation in order, so the sequence itself is assertable. */
    private class FakeSink(
        var services: String?,
        var enabled: Int
    ) : SecureSink {
        val calls = ArrayList<String>()
        override fun getString(key: String): String? {
            calls.add("get " + key)
            return services
        }
        override fun putString(key: String, value: String) {
            calls.add("put " + key + "=" + value)
            services = value
        }
        override fun putInt(key: String, value: Int) {
            calls.add("putInt " + key + "=" + value)
            enabled = value
        }
        override fun getInt(key: String, def: Int): Int = enabled
        override fun delete(key: String) {
            calls.add("delete " + key)
            services = null
        }
    }

    private val component = A11yOps.COMPONENT

    @Test
    fun theClearStepIsADeleteNeverAnEmptyString() {
        val sink = FakeSink(component, 1)
        A11yOps.reconnect(sink, {})
        assertTrue(sink.calls.toString(), sink.calls.contains("delete " + A11yOps.KEY_SERVICES))
        assertFalse(
            "an empty putString is rejected by the framework: " + sink.calls,
            sink.calls.any { it.startsWith("put " + A11yOps.KEY_SERVICES + "=") && it.endsWith("=") }
        )
    }

    @Test
    fun theSequenceIsDisableDeleteSleepWriteEnable() {
        val sink = FakeSink(component, 1)
        val sleeps = ArrayList<Long>()
        A11yOps.reconnect(sink, { sleeps.add(it) })
        assertEquals(
            listOf(
                "get " + A11yOps.KEY_SERVICES,
                "putInt " + A11yOps.KEY_ENABLED + "=0",
                "delete " + A11yOps.KEY_SERVICES,
                "put " + A11yOps.KEY_SERVICES + "=" + component,
                "putInt " + A11yOps.KEY_ENABLED + "=1"
            ),
            sink.calls
        )
        assertEquals(listOf(A11yOps.RESCUE_SLEEP_MS), sleeps)
    }

    @Test
    fun theSleepIsLongEnoughForTheFrameworkToReleaseTheBinding() {
        // Writing the entry back in the same millisecond leaves the old binding in
        // place: the framework compares the value, sees no change, and does nothing.
        assertTrue(A11yOps.RESCUE_SLEEP_MS >= 1_500L)
    }

    @Test
    fun theMasterSwitchIsOnAtTheEndAndOffDuringTheToggle() {
        val sink = FakeSink(component, 1)
        A11yOps.reconnect(sink, {})
        assertEquals(1, sink.enabled)
        val offAt = sink.calls.indexOf("putInt " + A11yOps.KEY_ENABLED + "=0")
        val writeAt = sink.calls.indexOf("put " + A11yOps.KEY_SERVICES + "=" + component)
        assertTrue("the switch must drop before the list is rewritten", offAt < writeAt)
    }

    @Test
    fun otherAccessibilityAppsArePreservedVerbatim() {
        val sink = FakeSink("com.other/.Svc:$component:com.third/.Svc", 1)
        A11yOps.reconnect(sink, {})
        assertEquals("com.other/.Svc:com.third/.Svc:$component", sink.services)
    }

    @Test
    fun ourEntryIsNotDuplicated() {
        val sink = FakeSink("$component:$component", 1)
        A11yOps.reconnect(sink, {})
        assertEquals(component, sink.services)
    }

    @Test
    fun aBlankOrNullListIsJustUs() {
        for (initial in listOf(null, "", ":", "::")) {
            val sink = FakeSink(initial, 0)
            A11yOps.reconnect(sink, {})
            assertEquals(initial.toString(), component, sink.services)
        }
    }

    @Test
    fun matchingOurEntryIgnoresCase() {
        assertEquals(
            listOf("com.other/.Svc"),
            A11yOps.others("com.other/.Svc:" + component.uppercase(), component)
        )
    }

    @Test
    fun theJoinedListNeverHasADoubleColon() {
        assertEquals(component, A11yOps.joined(emptyList(), component))
        assertEquals("a:$component", A11yOps.joined(listOf("a"), component))
    }

    @Test
    fun aMissingEntryIsHealedAndADeadBindingIsRescued() {
        assertEquals(A11yOps.ACTION_HEAL, A11yOps.repairAction(listed = false, live = false))
        assertEquals(A11yOps.ACTION_HEAL, A11yOps.repairAction(listed = false, live = true))
        // This is the v0.4.2 failure: listed, switch on, nothing bound.
        assertEquals(A11yOps.ACTION_RESCUE, A11yOps.repairAction(listed = true, live = false))
        assertEquals(A11yOps.ACTION_NONE, A11yOps.repairAction(listed = true, live = true))
    }

    @Test
    fun aLiveBindingIsNeverTouched() {
        val sink = FakeSink(component, 1)
        assertEquals(A11yOps.ACTION_NONE, A11yOps.repairAction(listed = true, live = true))
        assertEquals(component, sink.services)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun aBlankGetStringIsTreatedAsNoEntries() {
        assertTrue(A11yOps.others(null, component).isEmpty())
        assertTrue(A11yOps.others("  ", component).isEmpty())
        assertNull(FakeSink(null, 0).services)
    }
}
