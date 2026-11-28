package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「摄像头看图」的纯逻辑（v0.9.0-pad12）.
 *
 * These cases guard three kinds of mistake that never crash and never show up on
 * the phone: a blob name the relay answers 400 to, a note body whose field names
 * drifted away from what the VPS reads, and a sentence that says something other
 * than what happened. All three are only visible from the far side of the network,
 * which is exactly why they are pinned here.
 */
class CamPlanTest {

    @Test
    fun blobNameIsTheMillisAndTheSuffix() {
        assertEquals(
            "cam-1733456789012-a1b2.jpg",
            CamPlan.blobName(1_733_456_789_012L, "a1b2")
        )
    }

    @Test
    fun blobNameLowerCasesAnUpperCaseSuffix() {
        assertEquals("cam-1-A1B2.jpg".lowercase(), CamPlan.blobName(1L, "A1B2"))
    }

    @Test
    fun blobNameFallsBackWhenTheSuffixIsNotFourHexCharacters() {
        // Empty, too short, too long, and one illegal byte: all four are a 400 on
        // the relay if they are pasted into a URL, so none of them may pass.
        assertEquals("cam-7-0000.jpg", CamPlan.blobName(7L, ""))
        assertEquals("cam-7-0000.jpg", CamPlan.blobName(7L, "ab"))
        assertEquals("cam-7-0000.jpg", CamPlan.blobName(7L, "abcde"))
        assertEquals("cam-7-0000.jpg", CamPlan.blobName(7L, "a1-2"))
        assertEquals("cam-7-0000.jpg", CamPlan.blobName(7L, "a 12"))
    }

    @Test
    fun blobNameClampsANegativeClock() {
        assertEquals("cam-0-a1b2.jpg", CamPlan.blobName(-1L, "a1b2"))
        assertEquals("cam-0-a1b2.jpg", CamPlan.blobName(-1_733_456_789_012L, "a1b2"))
    }

    @Test
    fun blobNameFitsTheRelayNameWindow() {
        val name = CamPlan.blobName(1_733_456_789_012L, "ffff")
        assertTrue("名字超过 80 字符: " + name, name.length <= CamPlan.NAME_MAX_CHARS)
        for (ch in name) {
            val ok = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                ch == '.' || ch == '_' || ch == '-'
            assertTrue("名字里有中转站不接受的字符: " + ch + " in " + name, ok)
        }
    }

    @Test
    fun fitScaleBringsTheLongestSideToTheLimit() {
        assertEquals(0.5f, CamPlan.fitScale(2560, 1440, 1280), 0.0001f)
        assertEquals(0.5f, CamPlan.fitScale(1440, 2560, 1280), 0.0001f)
        assertEquals(1.0f / 3.0f, CamPlan.fitScale(3840, 2160, 1280), 0.0001f)
    }

    @Test
    fun fitScaleNeverEnlarges() {
        assertEquals(1.0f, CamPlan.fitScale(1280, 720, 1280), 0.0001f)
        assertEquals(1.0f, CamPlan.fitScale(640, 480, 1280), 0.0001f)
        assertEquals(1.0f, CamPlan.fitScale(1, 1, 1280), 0.0001f)
    }

    @Test
    fun fitScaleUsesWhicheverSideIsLonger() {
        // Portrait frame: the height is the longest side, not the width.
        assertEquals(0.5f, CamPlan.fitScale(720, 2560, 1280), 0.0001f)
    }

    @Test
    fun fitScaleRefusesIllegalNumbers() {
        assertEquals(1.0f, CamPlan.fitScale(0, 720, 1280), 0.0001f)
        assertEquals(1.0f, CamPlan.fitScale(1280, 0, 1280), 0.0001f)
        assertEquals(1.0f, CamPlan.fitScale(-1280, -720, 1280), 0.0001f)
        assertEquals(1.0f, CamPlan.fitScale(4000, 3000, 0), 0.0001f)
        assertEquals(1.0f, CamPlan.fitScale(4000, 3000, -5), 0.0001f)
    }

    @Test
    fun summaryLineUsesKilobytesAboveOneKiB() {
        assertEquals(
            "已发送：图 148 KB（1280x720）+ 文字 12 字",
            CamPlan.summaryLine(151_552, 1280, 720, 12)
        )
        assertEquals("已发送：图 1 KB（640x480）+ 文字 3 字", CamPlan.summaryLine(1024, 640, 480, 3))
    }

    @Test
    fun summaryLineUsesBytesBelowOneKiB() {
        assertEquals("已发送：图 0 B（1280x720）+ 文字 1 字", CamPlan.summaryLine(0, 1280, 720, 1))
        assertEquals("已发送：图 1023 B（1280x720）+ 文字 1 字", CamPlan.summaryLine(1023, 1280, 720, 1))
        assertEquals("已发送：图 0 B（1280x720）+ 文字 1 字", CamPlan.summaryLine(-9, 1280, 720, 1))
    }

    @Test
    fun summaryLineSaysZeroCharsWhenNothingWasTyped() {
        assertEquals("已发送：图 100 KB（1280x720）+ 文字 0 字", CamPlan.summaryLine(102_400, 1280, 720, 0))
    }

    @Test
    fun errorLineAlwaysNamesTheFailure() {
        assertEquals("发送失败: 网络断了", CamPlan.errorLine("网络断了"))
        assertEquals("发送失败: 未知原因", CamPlan.errorLine(""))
        assertEquals("发送失败: 未知原因", CamPlan.errorLine("   "))
    }

    @Test
    fun noteBodyCarriesExactlyTheSixFields() {
        assertEquals(
            "{\"id\":\"PHONE-MEIZU\",\"kind\":\"look\",\"text\":\"看下这个\"," +
                "\"blob\":\"cam-1733456789012-a1b2.jpg\",\"w\":1280,\"h\":720}",
            CamPlan.noteBody("PHONE-MEIZU", "看下这个", "cam-1733456789012-a1b2.jpg", 1280, 720)
        )
    }

    @Test
    fun noteBodyEscapesQuotesAndNewlines() {
        val body = CamPlan.noteBody("P", "他说\"停\"\n第二行\t结束", "cam-1-a1b2.jpg", 8, 9)
        assertTrue(body, body.contains("\"text\":\"他说\\\"停\\\"\\n第二行\\t结束\""))
    }

    @Test
    fun noteBodyEscapesControlCharacters() {
        val body = CamPlan.noteBody("P", "a\u0001b", "cam-1-a1b2.jpg", 1, 1)
        assertTrue(body, body.contains("\\u0001"))
    }

    @Test
    fun clampTextTrimsAndKeepsShortText() {
        assertEquals("看下这个", CamPlan.clampText("  看下这个 \n"))
        assertEquals("", CamPlan.clampText("   "))
    }

    @Test
    fun clampTextCutsAtFiveHundred() {
        val long = "字".repeat(700)
        assertEquals(CamPlan.MAX_TEXT, CamPlan.clampText(long).length)
        assertEquals(500, CamPlan.MAX_TEXT)
    }

    @Test
    fun clampTextDoesNotSplitASurrogatePair() {
        // 501 emoji: each is two chars, so a char-count cut at 500 would leave half
        // of the last one behind.
        val text = "😀".repeat(501)
        val cut = CamPlan.clampText(text)
        assertEquals(500, cut.codePointCount(0, cut.length))
        assertEquals(1000, cut.length)
        // A trailing *high* surrogate would be the half that has no partner; a
        // trailing low surrogate is the正常 tail of a complete pair.
        assertFalse("尾巴不能是半个代理对", Character.isHighSurrogate(cut[cut.length - 1]))
        assertEquals("😀", cut.substring(cut.length - 2))
    }

    @Test
    fun pickSizePrefersSixteenByNineNearTheTarget() {
        val picked = CamPlan.pickSize(
            listOf(
                intArrayOf(1920, 1080),
                intArrayOf(1280, 720),
                intArrayOf(640, 480),
                intArrayOf(320, 240)
            ),
            1280,
            720
        )
        assertNotNull(picked)
        assertEquals(1280, picked!![0])
        assertEquals(720, picked[1])
    }

    @Test
    fun pickSizeTakesTheClosestLongSideWhenOnlyFourByThreeIsOffered() {
        val picked = CamPlan.pickSize(
            listOf(intArrayOf(640, 480), intArrayOf(1440, 1080), intArrayOf(320, 240)),
            1280,
            720
        )
        assertNotNull(picked)
        assertEquals(1440, picked!![0])
        assertEquals(1080, picked[1])
    }

    @Test
    fun pickSizeReturnsNullWhenNothingIsUsable() {
        assertNull(CamPlan.pickSize(emptyList(), 1280, 720))
        assertNull(CamPlan.pickSize(listOf(intArrayOf(0, 0), intArrayOf(-4, 3)), 1280, 720))
        assertNull(CamPlan.pickSize(listOf(intArrayOf(1280, 720)), 0, 0))
    }
}
