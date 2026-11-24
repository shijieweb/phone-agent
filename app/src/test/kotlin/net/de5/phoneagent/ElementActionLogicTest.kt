package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framework-free half of the element-level actions (SPEC.md section 3.1):
 * finding the scroll target and turning a node's bounds into a tap point.
 *
 * The parts that call `AccessibilityNodeInfo` are not unit tested on purpose —
 * SPEC.md section 10 forbids mocking the accessibility service — but everything
 * that *decides* is pure and is covered here.
 */
class ElementActionLogicTest {

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

    // ------------------------------------------------------------ scroll target

    @Test
    fun aScrollableNodeIsItsOwnTarget() {
        val scroller = node(scrollable = true)
        assertEquals(scroller, ScrollTarget.at(scroller))
    }

    @Test
    fun theNearestScrollableDescendantIsUsed() {
        val inner = node(scrollable = true)
        val outer = node(children = listOf(node(), inner))
        assertEquals(inner, ScrollTarget.at(outer))
    }

    @Test
    fun aSubtreeWithoutAScrollerHasNoTarget() {
        assertNull(ScrollTarget.at(node(children = listOf(node(), node()))))
    }

    @Test
    fun firstScrollableWalksEveryRootInOrder() {
        val secondRootScroller = node(scrollable = true)
        val firstRoot = node(children = listOf(node()))
        val found = ScrollTarget.first(listOf(firstRoot, secondRootScroller))
        assertEquals(secondRootScroller, found)
    }

    @Test
    fun firstScrollableIsNullWhenNothingScrolls() {
        assertNull(ScrollTarget.first(listOf(node(), node(children = listOf(node())))))
    }

    // -------------------------------------------------------------- tap centre

    @Test
    fun theCentreOfBoundsIsTheMidpoint() {
        val centre = ElementActions.centreOf(NodeBounds(0, 0, 100, 50))
        assertEquals(50, centre!![0])
        assertEquals(25, centre[1])
    }

    @Test
    fun anOffsetRectCentresCorrectly() {
        val centre = ElementActions.centreOf(NodeBounds(10, 20, 40, 60))
        assertEquals(25, centre!![0])
        assertEquals(40, centre[1])
    }

    @Test
    fun aZeroAreaRectHasNoTapPoint() {
        // A node with no area cannot be tapped meaningfully.
        assertNull(ElementActions.centreOf(NodeBounds(5, 5, 5, 5)))
        assertNull(ElementActions.centreOf(NodeBounds(5, 5, 4, 10)))
        assertNull(ElementActions.centreOf(NodeBounds(5, 5, 10, 4)))
    }

    @Test
    fun missingBoundsHaveNoTapPoint() {
        assertNull(ElementActions.centreOf(null))
    }

    @Test
    fun anOffScreenOriginHasNoTapPoint() {
        // Bounds that start off-screen (a scrolled-away node) cannot be tapped.
        assertNull(ElementActions.centreOf(NodeBounds(-100, -100, 10, 10)))
    }

    // ------------------------------------------------------- confirm scanning

    /**
     * The bug this section exists for: `confirm zzqq_no_such_button` reported
     * success on a real tablet by tapping our own status line, because the
     * matcher only looked at text and the gesture fallback then tapped whatever
     * bounds the match happened to have. A label that cannot receive a click is
     * not a candidate.
     */
    @Test
    fun plainTextWithNoClickableAncestorIsNotACandidate() {
        val status = node(
            className = "android.widget.TextView",
            text = "服务: 运行中 / 无障碍: 已开启",
            bounds = NodeBounds(0, 900, 2000, 1000)
        )
        val scan = ElementActions.scanLabelIn("zzqq_no_such_button", listOf(status))
        assertNull(scan.first)
        assertEquals(0, scan.matches)

        val inert = ElementActions.scanLabelIn("运行中", listOf(status))
        assertNull(inert.first)
        assertEquals(1, inert.matches)
        assertEquals(1, inert.inertMatches)
    }

    @Test
    fun aClickableButtonIsACandidate() {
        val button = node(
            className = "android.widget.Button",
            text = "安装",
            bounds = NodeBounds(100, 100, 300, 200),
            clickable = true
        )
        val scan = ElementActions.scanLabelIn("安装", listOf(button))
        assertNotNull(scan.first)
        assertEquals(0, scan.first!!.hit!!.ancestorDepth)
        assertEquals(0, scan.inertMatches)
    }

    @Test
    fun aLabelInsideAClickableRowResolvesToTheRow() {
        // The label is not clickable by itself; its parent list row is. This is
        // the normal shape of a settings list on every ROM.
        val label = node(text = "继续安装", bounds = NodeBounds(10, 10, 300, 100))
        val row = FakeNode(
            clickable = true,
            bounds = NodeBounds(0, 0, 1000, 200),
            children = listOf(label)
        )
        val scan = ElementActions.scanLabelIn("继续安装", listOf(row))
        assertNotNull(scan.first)
        val hit = scan.first!!.hit!!
        assertEquals("继续安装", hit.node.text)
        assertTrue("depth must be > 0 when the click goes to an ancestor", hit.ancestorDepth > 0)
        assertEquals(0, scan.inertMatches)
    }

    @Test
    fun theInstallerPackageWinsOverAnIdenticalLabelElsewhere() {
        val otherApp = node(
            className = "android.widget.Button",
            text = "安装",
            bounds = NodeBounds(0, 1600, 400, 1700),
            clickable = true,
            packageName = "com.example.someapp"
        )
        val installer = node(
            className = "android.widget.Button",
            text = "安装",
            bounds = NodeBounds(1200, 900, 1900, 1000),
            clickable = true,
            packageName = "com.android.packageinstaller"
        )
        // The decoy comes first in walk order; the installer's button must still win.
        val scan = ElementActions.scanLabelIn("安装", listOf(otherApp, installer))
        assertNotNull(scan.first)
        assertEquals("com.android.packageinstaller", scan.first!!.hit!!.node.packageName)
        assertEquals(2, scan.matches)
    }

    private fun node(
        className: String? = null,
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        bounds: NodeBounds? = null,
        clickable: Boolean = false,
        scrollable: Boolean = false,
        packageName: String? = null,
        children: List<NodeView> = emptyList()
    ): FakeNode = FakeNode(
        className = className,
        packageName = packageName,
        text = text,
        desc = desc,
        resId = resId,
        bounds = bounds,
        clickable = clickable,
        scrollable = scrollable,
        children = children
    )
}
