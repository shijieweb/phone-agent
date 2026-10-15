package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun node(
        className: String? = null,
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        bounds: NodeBounds? = null,
        clickable: Boolean = false,
        scrollable: Boolean = false,
        children: List<NodeView> = emptyList()
    ): FakeNode = FakeNode(
        className = className,
        text = text,
        desc = desc,
        resId = resId,
        bounds = bounds,
        clickable = clickable,
        scrollable = scrollable,
        children = children
    )
}
