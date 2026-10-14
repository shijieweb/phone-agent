package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC.md section 10 case 3: the node-tree line format of section 5. */
class UiTreeTest {

    private class FakeNode(
        override val className: String? = null,
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
        override val children: List<NodeView> = emptyList()
    ) : NodeView

    @Test
    fun oneLinePerNodeInTheDocumentedColumnOrder() {
        val root = FakeNode(
            className = "android.widget.TextView",
            text = "Hi",
            bounds = NodeBounds(0, 0, 100, 50)
        )
        assertEquals("0|android.widget.TextView|Hi|||0,0,100,50|", UiTree.format(listOf(root), 400))
    }

    @Test
    fun everyWindowRootStartsAtDepthZero() {
        val first = FakeNode(className = "a", text = "one")
        val second = FakeNode(className = "b", text = "two")
        val out = UiTree.format(listOf(first, second), 400)
        assertEquals("0|a|one||||\n0|b|two||||", out)
    }

    @Test
    fun depthIncreasesWithNesting() {
        val child = FakeNode(className = "c", text = "kid")
        val root = FakeNode(className = "r", text = "root", children = listOf(child))
        assertEquals("0|r|root||||\n1|c|kid||||", UiTree.format(listOf(root), 400))
    }

    @Test
    fun flagLettersAreEmittedInTheDocumentedOrder() {
        val node = FakeNode(
            className = "x",
            text = "t",
            clickable = true,
            longClickable = true,
            focusable = true,
            editable = true,
            scrollable = true,
            checked = true,
            enabled = false,
            selected = true
        )
        assertEquals("0|x|t||||clfesxdk", UiTree.format(listOf(node), 400))
    }

    @Test
    fun onlyTheFlagsThatAreTrueAppear() {
        val node = FakeNode(className = "x", text = "t", scrollable = true)
        assertEquals("0|x|t||||s", UiTree.format(listOf(node), 400))
    }

    @Test
    fun textDescriptionAndResourceIdAreTruncatedToSixtyChars() {
        val long = "a".repeat(70)
        val node = FakeNode(className = "x", text = long, desc = long, resId = long, clickable = true)
        val line = UiTree.format(listOf(node), 400)
        val parts = line.split("|")
        // depth, class, text, desc, resId, bounds, flags
        assertEquals(7, parts.size)
        assertEquals("0", parts[0])
        assertEquals("x", parts[1])
        assertEquals("a".repeat(60), parts[2])
        assertEquals("a".repeat(60), parts[3])
        assertEquals("a".repeat(60), parts[4])
        assertEquals("", parts[5])
        assertEquals("c", parts[6])
    }

    @Test
    fun pipesAndNewlinesBecomeSlashes() {
        val node = FakeNode(className = "x", text = "a|b\nc\rd")
        assertEquals("0|x|a/b/c/d||||", UiTree.format(listOf(node), 400))
    }

    @Test
    fun emptyNodesWithoutChildrenAreSkipped() {
        val empty = FakeNode(className = "android.widget.FrameLayout")
        assertEquals("", UiTree.format(listOf(empty), 400))
    }

    @Test
    fun emptyContainersAreWalkedButNotEmitted() {
        val leaf = FakeNode(className = "c", text = "kid")
        val container = FakeNode(className = "android.widget.LinearLayout", children = listOf(leaf))
        assertEquals("1|c|kid||||", UiTree.format(listOf(container), 400))
    }

    @Test
    fun aNodeIsEmittedWhenOnlyItsFlagsAreInteresting() {
        val node = FakeNode(className = "x", clickable = true)
        assertEquals("0|x|||||c", UiTree.format(listOf(node), 400))
    }

    @Test
    fun theTreeStopsAtMaxNodesAndSaysSo() {
        val first = FakeNode(className = "a", text = "one")
        val second = FakeNode(className = "b", text = "two")
        val root = FakeNode(className = "r", children = listOf(first, second))
        val out = UiTree.format(listOf(root), 1)
        assertEquals("1|a|one||||\n... truncated at 1 nodes", out)
    }

    @Test
    fun maxNodesClampsToTheHardCap() {
        val nodes = ArrayList<NodeView>()
        var i = 0
        while (i < 3) {
            nodes.add(FakeNode(className = "n$i", text = "t$i"))
            i++
        }
        val out = UiTree.format(nodes, 5000)
        assertEquals(3, out.split("\n").size)
        assertTrue(!out.contains("truncated"))
    }

    @Test
    fun boundsAreRenderedAsLeftTopRightBottom() {
        val node = FakeNode(className = "x", text = "t", bounds = NodeBounds(1, 2, 3, 4))
        assertEquals("0|x|t|||1,2,3,4|", UiTree.format(listOf(node), 400))
    }
}
