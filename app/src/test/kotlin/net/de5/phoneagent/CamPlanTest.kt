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
    fun modeBodyOpenCarriesJustTheIdAndTheKind() {
        assertEquals(
            "{\"id\":\"PHONE-MEIZU\",\"kind\":\"cam-open\"}",
            CamPlan.modeBody("PHONE-MEIZU", CamPlan.MODE_OPEN)
        )
    }

    @Test
    fun modeBodyCloseCarriesJustTheIdAndTheKind() {
        assertEquals(
            "{\"id\":\"PHONE-MEIZU\",\"kind\":\"cam-close\"}",
            CamPlan.modeBody("PHONE-MEIZU", CamPlan.MODE_CLOSE)
        )
    }

    @Test
    fun modeBodyRejectsAnyOtherKind() {
        // The camera is a privacy switch: a third state nobody can explain later
        // must never reach the relay.
        assertEquals(
            "{\"id\":\"P\",\"kind\":\"\"}",
            CamPlan.modeBody("P", "cam-opening")
        )
        assertEquals(
            "{\"id\":\"P\",\"kind\":\"\"}",
            CamPlan.modeBody("P", "CAM-OPEN")
        )
        assertEquals(
            "{\"id\":\"P\",\"kind\":\"\"}",
            CamPlan.modeBody("P", "")
        )
    }

    @Test
    fun modeBodyEscapesTheId() {
        val body = CamPlan.modeBody("PHONE\"MEIZU\n2", CamPlan.MODE_OPEN)
        assertTrue(body, body.contains("\"id\":\"PHONE\\\"MEIZU\\n2\""))
    }

    @Test
    fun modeNoteAcceptsOnlyTheTwoWords() {
        assertTrue(CamPlan.modeNote(CamPlan.MODE_OPEN))
        assertTrue(CamPlan.modeNote(CamPlan.MODE_CLOSE))
        assertFalse(CamPlan.modeNote("look"))
        assertFalse(CamPlan.modeNote(""))
    }

    @Test
    fun modeBodyMatchesTheWordsTheRelayStores() {
        // rc-relay.py stores kind verbatim (truncated at 24 chars), so these two
        // strings are the whole contract with the operator's side.
        assertEquals("cam-open", CamPlan.MODE_OPEN)
        assertEquals("cam-close", CamPlan.MODE_CLOSE)
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

    // ------------------------------------------- 拍完可以动 (v0.12.0-pad12) --

    @Test
    fun holdLineSaysToStayStill() {
        assertEquals("正在拍，先别动", CamPlan.holdLine())
        assertEquals(CamPlan.HOLD_TEXT, CamPlan.holdLine())
    }

    @Test
    fun doneHoldLineSaysTheOwnerMayMove() {
        assertEquals("拍好了，可以动了（正在发送）", CamPlan.doneHoldLine())
        assertEquals(CamPlan.DONE_HOLD_TEXT, CamPlan.doneHoldLine())
    }

    @Test
    fun theTwoStagesNeverLookAlike() {
        // The whole feature is this inequality: one sentence has to mean "hold
        // still", the other "you are free". If they ever collapse into the same
        // string the owner is back to guessing, and nothing else would notice.
        assertFalse(CamPlan.HOLD_TEXT == CamPlan.DONE_HOLD_TEXT)
        assertFalse(CamPlan.holdLine() == CamPlan.doneHoldLine())
    }

    @Test
    fun doneHoldLineCarriesThePermissionToMove() {
        // A vaguer rewording ("处理中…") would pass every other test while losing the
        // one word the owner is waiting for.
        assertTrue(CamPlan.DONE_HOLD_TEXT, CamPlan.DONE_HOLD_TEXT.contains("可以动"))
        assertTrue(CamPlan.holdLine(), CamPlan.holdLine().contains("别动"))
    }

    @Test
    fun holdTextsAreShortEnoughForOneLine() {
        // The status line sits above a text box on a phone held at arm's length; a
        // sentence that wraps is a sentence that is not read.
        assertTrue(CamPlan.HOLD_TEXT.length <= 20)
        assertTrue(CamPlan.DONE_HOLD_TEXT.length <= 24)
    }

    @Test
    fun retryLineTellsTheOwnerWhatToDoNext() {
        assertEquals("没传上去，再按一次发送", CamPlan.retryLine())
        assertTrue(CamPlan.retryLine().contains("再按一次"))
    }

    @Test
    fun closeCameraTextSaysItClosesTheCamera() {
        // The top-right button replaced 返回, which sat next to 发送 and was pressed by
        // mistake. This word is the whole difference, so pin it.
        assertEquals("关闭摄像头", CamPlan.CLOSE_CAMERA_TEXT)
    }

    @Test
    fun retryLineIsNotAnErrorDump() {
        // The failure detail belongs in AgentLog: the screen must not carry a class
        // name or an exception string that the person holding the phone cannot use.
        val line = CamPlan.retryLine()
        assertFalse(line, line.contains("Exception"))
        assertFalse(line, line.contains(":"))
    }


    // ------------------------------------------- 相机开关 (v0.11.4-pad12) --

    @Test
    fun openCameraTextIsNotTheCloseLabel() {
        // The button says what the next tap does. If the two labels ever collapse
        // into one string, the switch has no visible state and the owner cannot tell
        // whether the next 发送 carries a picture -- nothing else would notice.
        assertEquals("打开摄像头", CamPlan.OPEN_CAMERA_TEXT)
        assertFalse(CamPlan.OPEN_CAMERA_TEXT == CamPlan.CLOSE_CAMERA_TEXT)
    }

    @Test
    fun camOffTextPromisesATextOnlySend() {
        // The placeholder is the only thing that distinguishes "camera switched off"
        // from "camera stalled on the last frame", and it has to promise the thing the
        // owner cares about: this send goes out without a picture.
        assertTrue(CamPlan.CAM_OFF_TEXT, CamPlan.CAM_OFF_TEXT.contains("只发文字"))
        assertTrue(CamPlan.CAM_OFF_TEXT, CamPlan.CAM_OFF_TEXT.contains("已关闭"))
    }

    @Test
    fun textOnlyLineNamesTheCharacterCount() {
        assertEquals("已发送：文字 12 字（没带图）", CamPlan.textOnlyLine(12))
    }

    @Test
    fun textOnlyLineSaysNoPictureInItsOwnWords() {
        // Not summaryLine with zeros in it: 图 0 B（0x0）reads as "a picture went, and
        // it was empty", which is the one thing the owner must not think.
        assertTrue(CamPlan.textOnlyLine(12).contains("没带图"))
        assertFalse(CamPlan.textOnlyLine(12).contains("图 0 B"))
    }

    @Test
    fun textOnlyLineFallsBackWhenTheCountIsNotUsable() {
        // The send happened, so the sentence still has to say so -- just without a
        // number that would be a lie.
        assertEquals("已发送：文字（没带图）", CamPlan.textOnlyLine(0))
        assertEquals("已发送：文字（没带图）", CamPlan.textOnlyLine(-3))
    }

    @Test
    fun aTextOnlyNoteHasAnEmptyBlobAndNoSize() {
        // This is the whole wire contract of the text-only path: the relay accepts an
        // empty blob name (it is the "no picture" spelling) and stores w/h verbatim.
        // A blob name of "null", "0" or a leftover cam-*.jpg would make the far side
        // fetch a picture that was never uploaded.
        val body = CamPlan.noteBody("PHONE-MEIZU", "只发文字", "", 0, 0)
        assertTrue(body, body.contains("\"kind\":\"look\""))
        assertTrue(body, body.contains("\"blob\":\"\""))
        assertTrue(body, body.contains("\"w\":0"))
        assertTrue(body, body.contains("\"h\":0"))
        assertFalse(body, body.contains(".jpg"))
    }

    @Test
    fun pickSizeReturnsNullWhenNothingIsUsable() {
        assertNull(CamPlan.pickSize(emptyList(), 1280, 720))
        assertNull(CamPlan.pickSize(listOf(intArrayOf(0, 0), intArrayOf(-4, 3)), 1280, 720))
        assertNull(CamPlan.pickSize(listOf(intArrayOf(1280, 720)), 0, 0))
    }


    // ------------------------------------------- 回执 (v0.11.5-pad12) --

    @Test
    fun uploadedLineNamesTheSizeAndTheCharacterCount() {
        // Same numbers as the old summary line, so the operator recognises the send
        // he just made -- only the claim at the end of it changed.
        assertEquals(
            "已上传：图 148 KB（1280x720）+ 文字 12 字，等我确认",
            CamPlan.uploadedLine(151_552, 1280, 720, 12)
        )
        assertEquals("已上传：图 1 KB（640x480）+ 文字 3 字，等我确认", CamPlan.uploadedLine(1024, 640, 480, 3))
    }

    @Test
    fun uploadedLineAsksForConfirmationInsteadOfClaimingItWasSeen() {
        // The whole point of v0.11.5: the relay storing the note proves the phone got
        // through, not that anyone looked. A line that says 已读 here would be a lie
        // the owner would repeat to the person in front of him.
        val line = CamPlan.uploadedLine(151_552, 1280, 720, 12)
        assertTrue(line, line.contains("等我确认"))
        assertFalse(line, line.contains(CamPlan.readLine()))
    }

    @Test
    fun uploadedLineIsNotTheOldSentSentence() {
        // 已发送 was the only thing the phone could ever prove before this version;
        // keeping it would leave the owner reading "sent" as "seen".
        val line = CamPlan.uploadedLine(1024, 640, 480, 3)
        assertFalse(line, line.startsWith("已发送"))
        assertTrue(line, line.startsWith("已上传"))
    }

    @Test
    fun uploadedTextOnlyLineNamesTheCharacterCount() {
        assertEquals("已上传：文字 12 字（没带图），等我确认", CamPlan.uploadedTextOnlyLine(12))
    }

    @Test
    fun uploadedTextOnlyLineFallsBackWhenTheCountIsNotUsable() {
        // A picture-less send still happened, so it still has to say 没带图 rather than
        // looking like a picture that came out empty.
        assertEquals("已上传：文字（没带图），等我确认", CamPlan.uploadedTextOnlyLine(0))
        assertEquals("已上传：文字（没带图），等我确认", CamPlan.uploadedTextOnlyLine(-3))
    }

    @Test
    fun everyPendingLineAsksForConfirmation() {
        // Both shapes of the pending line -- with a picture and without -- have to
        // carry the same promise, otherwise one of the two paths silently keeps the
        // old "sent, therefore seen" reading.
        assertTrue(CamPlan.uploadedLine(1024, 640, 480, 3), CamPlan.uploadedLine(1024, 640, 480, 3).contains("等我确认"))
        assertTrue(CamPlan.uploadedTextOnlyLine(3), CamPlan.uploadedTextOnlyLine(3).contains("等我确认"))
    }

    @Test
    fun readLineIsTheShortAnswer() {
        // Two characters, readable from across a room: it is the one line on the page
        // that is a fact about the operator rather than about the phone.
        assertEquals("已读", CamPlan.readLine())
        assertEquals(2, CamPlan.readLine().length)
    }

    @Test
    fun unreadLineSaysTheServerHasItAndNobodyLooked() {
        // Not an error: nothing failed. The note is waiting, and the sentence has to
        // say both halves -- it arrived, nobody has opened it.
        assertEquals("服务器收到了，但还没人看", CamPlan.unreadLine())
        assertTrue(CamPlan.unreadLine(), CamPlan.unreadLine().contains("服务器收到了"))
        assertTrue(CamPlan.unreadLine(), CamPlan.unreadLine().contains("还没人看"))
    }

    @Test
    fun readAndUnreadAreDifferentSentences() {
        // If these two ever collapse, the ack thread can no longer be tested at all:
        // the owner would see the same line whether or not anyone looked.
        assertFalse(CamPlan.readLine() == CamPlan.unreadLine())
        assertFalse(CamPlan.readLine().contains("还没人看"))
        assertFalse(CamPlan.unreadLine().contains("等我确认"))
    }
}
