package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 `tree uia` 的解析器（v0.6.1-pad12）。
 *
 * 这条通道存在的唯一理由是**新鲜**：无障碍读到的窗口内容恒慢一拍，uiautomator
 * dump 读到的是当场视图树。所以解析器一旦把行格式弄偏，两条通道就不能互相替换，
 * 操作员也就没法用它验证无障碍那条路。这里钉三件事：depth 从嵌套算出来、属性到
 * flags 字母的映射与无障碍一致、截断行照旧。
 */
class UiaDumpTest {

    private fun dump(inner: String): String =
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" +
            "<hierarchy rotation=\"0\">" + inner + "</hierarchy>"

    private fun node(attrs: String, children: String = ""): String =
        if (children.isEmpty()) "<node $attrs />" else "<node $attrs>$children</node>"

    @Test
    fun threeNestedLevelsBecomeDepthsZeroOneTwo() {
        val xml = dump(
            node(
                "class=\"android.widget.FrameLayout\" resource-id=\"android:id/content\" bounds=\"[0,0][1080,2400]\"",
                node(
                    "class=\"android.widget.LinearLayout\" resource-id=\"android:id/root\" bounds=\"[0,100][1080,900]\"",
                    node("class=\"android.widget.EditText\" text=\"relay\" bounds=\"[40,200][1000,300]\"")
                )
            )
        )
        val lines = UiaDump.parse(xml, 100).split("\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("0|android.widget.FrameLayout|"))
        assertTrue(lines[1].startsWith("1|android.widget.LinearLayout|"))
        assertTrue(lines[2].startsWith("2|android.widget.EditText|relay|"))
    }

    @Test
    fun boundsAreReadOffTheBracketForm() {
        val line = UiaDump.parse(dump(node("class=\"android.widget.TextView\" text=\"x\" bounds=\"[12,34][56,78]\"")), 10)
        assertEquals("0|android.widget.TextView|x|||12,34,56,78|", line)
    }

    @Test
    fun clickableBecomesC() {
        val line = UiaDump.parse(dump(node("class=\"android.widget.Button\" text=\"启动\" clickable=\"true\"")), 10)
        assertTrue(line.endsWith("|c"))
    }

    @Test
    fun checkedBecomesX() {
        val line = UiaDump.parse(
            dump(node("class=\"android.widget.Switch\" text=\"屏幕常亮\" clickable=\"true\" checked=\"true\"")),
            10
        )
        assertTrue(line.endsWith("|cx"))
    }

    @Test
    fun disabledBecomesD() {
        val line = UiaDump.parse(dump(node("class=\"android.widget.Button\" text=\"停止\" enabled=\"false\"")), 10)
        assertTrue(line.endsWith("|d"))
    }

    @Test
    fun anEditTextCarriesEWithoutAnEditableAttribute() {
        // uiautomator 不输出 editable；无障碍通道对 EditText 恒报 e。
        // 两条通道必须一致，否则同一个输入框在脚本里要写两套判断。
        val line = UiaDump.parse(dump(node("class=\"android.widget.EditText\" text=\"id\"")), 10)
        assertTrue(line.endsWith("|e"))
    }

    @Test
    fun aNodeWithNothingOnItIsSkippedButItsChildrenAreNot() {
        val xml = dump(
            node(
                "class=\"android.widget.FrameLayout\"",
                node("class=\"android.widget.TextView\" text=\"里面有字\"")
            )
        )
        val lines = UiaDump.parse(xml, 10).split("\n")
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("1|android.widget.TextView|里面有字|"))
    }

    @Test
    fun moreNodesThanTheBudgetStopsAndSaysSo() {
        val kids = (1..5).joinToString("") { node("class=\"android.widget.TextView\" text=\"row" + it + "\"") }
        val lines = UiaDump.parse(dump(node("class=\"android.widget.LinearLayout\"", kids)), 3).split("\n")
        assertEquals(4, lines.size)
        assertEquals("... truncated at 3 nodes", lines[3])
    }

    @Test
    fun anEmptyDumpIsAnEmptyAnswerNotACrash() {
        assertEquals("", UiaDump.parse("", 100))
        assertEquals("", UiaDump.parse("随便一段没有 hierarchy 的回显", 100))
        assertEquals("", UiaDump.parse("<hierarchy rotation=\"0\"></hierarchy>", 100))
    }

    @Test
    fun theEchoAroundTheDumpIsIgnored() {
        // PTY 会把命令行和提示符一起吐回来；真正的 XML 夹在中间。
        val noisy = "uiautomator dump /data/local/tmp/phoneagent-tree.xml\n" +
            "UI hierchary dumped to: /data/local/tmp/phoneagent-tree.xml\n" +
            dump(node("class=\"android.widget.TextView\" text=\"ok\"")) + "\n__RC=0\n"
        assertEquals("0|android.widget.TextView|ok||||", UiaDump.parse(noisy, 10))
    }

    @Test
    fun aValueCarryingAGreaterThanSignDoesNotEndTheTag() {
        val line = UiaDump.parse(dump(node("class=\"android.widget.TextView\" text=\"a &gt; b\"")), 10)
        assertEquals("0|android.widget.TextView|a > b||||", line)
    }

    @Test
    fun thePipeInATextIsNeutralisedLikeTheAccessibilityPath() {
        // 行格式用 | 分隔，值里的 | 必须换掉，否则一条文本会把整行拆错列。
        val line = UiaDump.parse(dump(node("class=\"android.widget.TextView\" text=\"a|b\"")), 10)
        assertEquals("0|android.widget.TextView|a/b||||", line)
    }

    @Test
    fun theMarkersAreReadFromTheirOwnLineOnly() {
        // PTY 回显里有同名的 echo __UIA_OK__，按 contains 判会被骗。
        val echoed = "if uiautomator dump x; then echo __UIA_OK__; fi; cat x\n"
        assertFalse(UiaDump.succeeded(echoed))
        assertTrue(UiaDump.succeeded(echoed + "__UIA_OK__\n"))
        assertTrue(UiaDump.failed(echoed + "__UIA_FAIL__\n"))
        assertFalse(UiaDump.succeeded(echoed + "__UIA_FAIL__\n"))
    }

    @Test
    fun theDumpCommandKeepsTheFileForEvidence() {
        // 读完不删：真机上要靠这份 XML 手动取证。
        assertTrue(UiaDump.dumpCommand().contains(UiaDump.DUMP_PATH))
        assertFalse(UiaDump.dumpCommand().contains("rm "))
    }

    @Test
    fun theParserTakesTheSameClampAsTheAccessibilityPath() {
        // 两条通道的上限必须一致，否则同一个 maxNodes 在两路上给出不同的截断点。
        val kids = (1..900).joinToString("") { node("class=\"android.widget.TextView\" text=\"row" + it + "\"") }
        val lines = UiaDump.parse(dump(node("class=\"android.widget.LinearLayout\"", kids)), 5000).split("\n")
        assertEquals("... truncated at " + UiTree.HARD_MAX_NODES + " nodes", lines[lines.size - 1])
    }
}
