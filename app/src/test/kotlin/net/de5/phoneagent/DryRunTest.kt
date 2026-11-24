package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dry-run line of \`click\`/\`longclick\`/\`confirm\` (SPEC.md section 3.1).
 *
 * A dry run must name the element, the path it would take and where it would
 * land -- that line is the whole value of not tapping, so its exact shape is
 * pinned here.
 */
class DryRunTest {

    private class FakeNode(
        override val className: String? = null,
        override val packageName: String? = null,
        override val text: String? = null,
        override val desc: String? = null,
        override val resId: String? = null,
        override val bounds: NodeBounds? = null,
        override val clickable: Boolean = false,
        override val longClickable: Boolean = false,
        override val focusable: Boolean = false,
        override val editable: Boolean = false,
        override val scrollable: Boolean = false,
        override val checked: Boolean = false,
        override val enabled: Boolean = true,
        override val selected: Boolean = false,
        override val visibleToUser: Boolean = true,
        override val children: List<NodeView> = emptyList()
    ) : NodeView

    private fun line(verb: String, text: String, roots: List<NodeView>): String {
        val read = ElementActions.inspect(Selector.parse(text), roots)
        return ElementActions.dryRunLine(verb, text, read)
    }

    @Test
    fun aClickableNodeWouldBePressedWithTheActionPath() {
        val button = node(
            text = "登录",
            packageName = "com.example.app",
            clickable = true,
            bounds = NodeBounds(10, 20, 110, 60)
        )
        assertEquals(
            "dry-run: would click \"登录\" via=action pkg=com.example.app node=登录 at=60,40",
            line("click", "登录", listOf(button))
        )
    }

    @Test
    fun aTextNodeInsideAClickableRowWouldBePressedThroughTheAncestor() {
        val row = node(
            packageName = "com.example.app",
            clickable = true,
            bounds = NodeBounds(0, 100, 200, 200),
            children = listOf(node(text = "下一步"))
        )
        assertEquals(
            "dry-run: would click \"下一步\" via=ancestor pkg=com.example.app node=下一步 at=100,150",
            line("click", "下一步", listOf(row))
        )
    }

    @Test
    fun aNodeWithoutAClickableTargetWouldFallBackToAGesture() {
        val label = node(
            text = "只读文本",
            packageName = "com.example.app",
            bounds = NodeBounds(0, 0, 40, 40)
        )
        assertEquals(
            "dry-run: would longclick \"只读文本\" via=gesture pkg=com.example.app node=只读文本 at=20,20",
            line("longclick", "只读文本", listOf(label))
        )
    }

    @Test
    fun aNodeWithoutBoundsReportsNoCoordinate() {
        val button = node(text = "确定", packageName = "p", clickable = true)
        assertEquals(
            "dry-run: would confirm \"确定\" via=action pkg=p node=确定 at=?",
            line("confirm", "确定", listOf(button))
        )
    }

    @Test
    fun aResIdIsPreferredOverTheTextAsTheNodeLabel() {
        val button = node(
            text = "确定",
            resId = "com.example.app:id/ok",
            packageName = "com.example.app",
            clickable = true,
            bounds = NodeBounds(0, 0, 10, 10)
        )
        assertEquals(
            "dry-run: would click \"确定\" via=action pkg=com.example.app node=com.example.app:id/ok at=5,5",
            line("click", "确定", listOf(button))
        )
    }

    @Test
    fun theVerbIsTheOnlyThingThatChangesBetweenTheThreeDryRuns() {
        val button = node(
            text = "安装",
            packageName = "com.android.packageinstaller",
            clickable = true,
            bounds = NodeBounds(0, 0, 20, 20)
        )
        val roots = listOf(button)
        assertEquals(
            "dry-run: would click \"安装\" via=action pkg=com.android.packageinstaller node=安装 at=10,10",
            line("click", "安装", roots)
        )
        assertEquals(
            "dry-run: would confirm \"安装\" via=action pkg=com.android.packageinstaller node=安装 at=10,10",
            line("confirm", "安装", roots)
        )
    }

    private fun node(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        packageName: String? = null,
        bounds: NodeBounds? = null,
        clickable: Boolean = false,
        children: List<NodeView> = emptyList()
    ): NodeView = FakeNode(
        className = "android.widget.TextView",
        packageName = packageName,
        text = text,
        desc = desc,
        resId = resId,
        bounds = bounds,
        clickable = clickable,
        children = children
    )
}
