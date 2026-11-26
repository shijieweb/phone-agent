package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `status` (v0.8.0-pad12, SPEC.md §3.23).
 *
 * The controller reads this JSON by field name: renaming or reordering a key
 * breaks it. v0.8.0 appends `shell` and touches nothing else.
 */
class StatusPlanTest {

    @Test
    fun thereAreTwelveKeysInTheDocumentedOrder() {
        assertEquals(
            listOf("ok", "ver", "acc", "svc", "scr", "bat", "loc", "loop", "opt", "queue", "guard", "shell"),
            StatusPlan.KEYS
        )
        assertEquals(12, StatusPlan.KEYS.size)
    }

    @Test
    fun theElevenOlderKeysKeepTheirNamesAndPositions() {
        assertEquals(
            listOf("ok", "ver", "acc", "svc", "scr", "bat", "loc", "loop", "opt", "queue", "guard"),
            StatusPlan.KEYS.take(11)
        )
        assertEquals("shell", StatusPlan.KEYS.last())
    }

    @Test
    fun theFieldsFollowTheKeyOrder() {
        val fields = StatusPlan.fields(
            ver = "0.8.0-pad12",
            acc = true,
            svc = false,
            scr = true,
            bat = 88,
            loc = true,
            loop = 60,
            opt = "on",
            queue = 0,
            guard = "idle",
            shellConnected = true
        )
        assertEquals(StatusPlan.KEYS, fields.map { it.first })
        assertEquals("0.8.0-pad12", fields[1].second)
        assertEquals(88, fields[5].second)
        assertEquals("idle", fields[10].second)
        assertEquals("ok", fields[11].second)
    }

    @Test
    fun shellIsOkWhenConnectedAndDownOtherwise() {
        assertEquals("ok", StatusPlan.shellFlag(true))
        assertEquals("down", StatusPlan.shellFlag(false))
        assertEquals("down", StatusPlan.fields(
            ver = "v", acc = false, svc = false, scr = false, bat = 0, loc = false,
            loop = 0, opt = "off", queue = 0, guard = "idle", shellConnected = false
        ).last().second)
    }
}
