package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App 侧停止标记（v0.6.5-pad12）。
 *
 * 钉住的是一个真机报障：老板点了「停止」，界面每 60 秒自己弹回前台。原因不是服务
 * 没停，而是**停脚本这件事只能经自连 ADB 做**，走蜂窝时写不了 /data/local/tmp、
 * 也 kill 不了进程，脚本照旧每轮发现服务没了就把界面拉起来。
 *
 * 所以标记换到 /sdcard：App 有「所有文件访问」就能写根目录，shell 在 sdcard_rw
 * 组里就能读。这里只测纯文件逻辑（不碰 Context），因为真机上「/sdcard 写不了」
 * 是权限问题，逻辑本身必须幂等且绝不抛。
 */
class StopFlagTest {

    @Test
    fun theFlagLivesOnTheSharedVolumeWithTheExactPathTheScriptGreps() {
        assertEquals(".phoneagent-stop", StopFlag.FILE_NAME)
        assertEquals("/sdcard/.phoneagent-stop", StopFlag.PATH)
        // 脚本里的字面量来自 WatchdogPlan.STOP_MARKER，两边必须是同一个串。
        assertEquals(StopFlag.PATH, WatchdogPlan.STOP_MARKER)
        // 反证：不能落在 /data/local/tmp（那是 App uid 写不了的地方，也是本次报障的根）。
        assertFalse(StopFlag.PATH.startsWith("/data/local/tmp"))
    }

    @Test
    fun setThenClearIsIdempotentAndNeverThrows() {
        val file = File.createTempFile("stopflag", ".tmp")
        file.delete()
        try {
            assertFalse("一开始没有", StopFlag.isSet(file))
            assertTrue("第一次写", StopFlag.set(file))
            assertTrue("写完就在", StopFlag.isSet(file))
            assertTrue("重复写也算成功（覆盖同一个字节）", StopFlag.set(file))
            assertTrue("写完还在", StopFlag.isSet(file))
            assertTrue("第一次删", StopFlag.clear(file))
            assertFalse("删完就没了", StopFlag.isSet(file))
            assertTrue("本来就没有也算成功", StopFlag.clear(file))
            assertFalse("清完之后 isSet 仍是 false", StopFlag.isSet(file))
        } finally {
            file.delete()
        }
    }

    /**
     * 写不下时要返回 false，不能抛——抛出去会把「停止」这个动作整个打断，
     * 老板按了停止却什么也没发生，比弹窗更难解释。
     */
    @Test
    fun aPathThatCannotBeWrittenReportsFalseInsteadOfThrowing() {
        // 目录当成文件用：写入必然失败（Is a directory / FileNotFound）。
        val dir = File.createTempFile("stopflag-dir", ".tmp")
        dir.delete()
        assertTrue(dir.mkdirs())
        try {
            assertFalse("写不进目录", StopFlag.set(dir))
            assertFalse("目录也不算标记", StopFlag.isSet(dir))
        } finally {
            dir.delete()
        }
    }

    @Test
    fun theParentDirectoryIsCreatedWhenMissing() {
        val dir = File.createTempFile("stopflag-parent", ".tmp")
        dir.delete()
        assertTrue(dir.mkdirs())
        val nested = File(File(dir, "sub"), "flag")
        try {
            assertTrue("父目录缺了要自己建", StopFlag.set(nested))
            assertTrue(StopFlag.isSet(nested))
            assertTrue(StopFlag.clear(nested))
            assertFalse(StopFlag.isSet(nested))
        } finally {
            File(dir, "sub").delete()
            dir.delete()
        }
    }

    @Test
    fun clearForArmOnlyReportsWhatItActuallyCleared() {
        val file = File.createTempFile("stopflag-arm", ".tmp")
        file.delete()
        try {
            assertFalse("本来就没有：不算清掉", StopFlag.clearForArm(file))
            StopFlag.set(file)
            assertTrue("真清掉了才报 true", StopFlag.clearForArm(file))
            assertFalse("清完之后就没有了", StopFlag.isSet(file))
        } finally {
            file.delete()
        }
    }
}
