package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 `logs` 的选路（v0.5.9-pad12）。
 *
 * 真机上的缺陷是：Android 16 用 App 的 uid 读日志会弹系统授权框，只给「允许
 * 一次」，每次重弹 —— 远程操作员点不到，`logs` 在无人值守的机器上等于不可用。
 * 而 uid 2000 的 logcat 不弹框。所以「连上自连 ADB 就必须走 ADB」不是优化，
 * 是这条命令能不能用的分界线；这条规则如果哪天被改回无条件 App 自读，
 * 真机上不会报错，只会安静地弹一个没人点的框。
 */
class LogSourceTest {

    @Test
    fun aConnectedAdbIsAlwaysPreferred() {
        val plan = LogSource.plan(adbConnected = true, lines = 50)
        assertTrue("连上了就必须走自连 ADB，否则真机上会弹框", plan is LogSource.Plan.Adb)
        assertEquals("logcat -d -t 50", (plan as LogSource.Plan.Adb).command)
    }

    @Test
    fun withoutAdbWeFallBackToTheAppReadingItself() {
        val plan = LogSource.plan(adbConnected = false, lines = 50)
        assertTrue(plan is LogSource.Plan.Local)
        assertEquals(
            listOf("logcat", "-d", "-t", "50"),
            (plan as LogSource.Plan.Local).arguments
        )
    }

    @Test
    fun bothPathsClampTheSameWay() {
        assertEquals(1, LogSource.clamp(0))
        assertEquals(1, LogSource.clamp(-7))
        assertEquals(LogSource.MAX_LINES, LogSource.clamp(LogSource.MAX_LINES + 1))
        assertEquals(50, LogSource.clamp(50))
    }

    @Test
    fun aHugeRequestIsClampedOnTheAdbPathToo() {
        val plan = LogSource.plan(adbConnected = true, lines = 100_000)
        assertEquals("logcat -d -t " + LogSource.MAX_LINES, (plan as LogSource.Plan.Adb).command)
    }

    @Test
    fun theAdbCommandIsADumpNotAFollow() {
        // -d 是「dump 完就退出」。少了它，这条命令会一直挂着等新日志，
        // 在 relay 上表现为超时，而不是「日志有点长」。
        val command = LogSource.adbCommand(20)
        assertTrue(command.contains(" -d"))
        assertTrue(command.startsWith("logcat"))
    }

    @Test
    fun theLocalArgumentsStillStartWithTheProgramName() {
        // ProcessBuilder 的第一个元素是程序名，不是参数。丢了它整条命令就跑不起来。
        assertEquals("logcat", LogSource.localArguments(20).first())
    }

    @Test
    fun theEmptyAnswerSaysWhyAndWhereToGo() {
        val text = LogSource.localEmptyText(30)
        assertTrue("要说清是系统弹框，不是没授权", text.contains("弹系统授权框"))
        assertTrue("要说清每次都要重弹，所以远程点不了", text.contains("每次都要重弹"))
        assertTrue("要指路去自连 ADB", text.contains("shconnect"))
        assertTrue("要给一条能直接抄的兜底命令", text.contains("sh apply logcat -d -t 30"))
    }

    @Test
    fun theEmptyAnswerDoesNotSendAnyoneToPmGrant() {
        // pm grant 之后 dumpsys 显示 granted=true，弹框照旧。文案里提到它只能是
        // 为了说「没用」，绝不能出现在「请执行」的位置。
        val text = LogSource.localEmptyText(30)
        val index = text.indexOf("pm grant")
        assertTrue("必须明确说 pm grant 没用", index > 0)
        assertTrue(
            "提到 pm grant 的那句必须是否定的",
            text.substring(index).contains("没用") || text.substring(index).contains("也没用")
        )
    }

    @Test
    fun theClampMatchesWhatTheParserAccepts() {
        // 两个地方各钳一次就会对不上：解析层把 0 钳成 1，这里也得是 1。
        assertEquals(Commands.LOGS_LINES_MAX, LogSource.MAX_LINES)
        assertEquals(1, LogSource.clamp(0))
    }
}
