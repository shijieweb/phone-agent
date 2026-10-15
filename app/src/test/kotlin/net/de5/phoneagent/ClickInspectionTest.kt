package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The click/longclick *inspection* step: what `ElementActions` decides before it
 * touches the framework (SPEC.md section 3.1).
 *
 * `inspect` is pure over [NodeView] — the framework calls happen afterwards and
 * are covered by the manual checks in `manualcheck.md`.
 */
class ClickInspectionTest {

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

    @Test
    fun aMissReportsNoHitAndZeroMatches() {
        val result = ElementActions.inspect(Selector.parse("nothing"), listOf(node(text = "hello")))
        assertNull(result.hit)
        assertEquals(0, result.matches)
        assertEquals("matches=0", result.matchesText)
    }

    @Test
    fun aHitCarriesItsTargetCentreAndMatchCount() {
        val target = node(
            text = "Buy",
            clickable = true,
            bounds = NodeBounds(0, 0, 100, 40)
        )
        val result = ElementActions.inspect(Selector.parse("Buy"), listOf(target))
        assertNotNull(result.hit)
        assertEquals(target, result.hit?.node)
        assertEquals(target, result.target)
        assertEquals(50, result.centre!![0])
        assertEquals(20, result.centre!![1])
        assertEquals(1, result.matches)
    }

    @Test
    fun theTapPointComesFromTheActionTargetsBounds() {
        // The hit is the inner text, but the clickable row is what will be
        // tapped, so the centre must be the row's.
        val inner = node(text = "Buy", bounds = NodeBounds(10, 10, 20, 20))
        val row = node(clickable = true, bounds = NodeBounds(0, 0, 200, 100), children = listOf(inner))
        val result = ElementActions.inspect(Selector.parse("Buy"), listOf(row))
        assertEquals(inner, result.hit?.node)
        assertEquals(row, result.target)
        assertEquals(100, result.centre!![0])
        assertEquals(50, result.centre!![1])
    }

    @Test
    fun anAmbiguousSelectorStillReturnsTheFirstMatch() {
        val first = node(text = "Save")
        val second = node(text = "Save as")
        val result = ElementActions.inspect(Selector.parse("Save"), listOf(first, second))
        assertEquals(first, result.hit?.node)
        // …but the count tells the operator the selector was ambiguous.
        assertEquals(2, result.matches)
        assertEquals("matches=2", result.matchesText)
    }

    @Test
    fun aHitWithoutBoundsHasNoTapPointButIsStillAHit() {
        val target = node(text = "Buy", clickable = true, bounds = null)
        val result = ElementActions.inspect(Selector.parse("Buy"), listOf(target))
        assertNotNull(result.hit)
        assertNull(result.centre)
    }

    @Test
    fun anUnclickableHitWithoutAClickableAncestorHasNoActionTarget() {
        val target = node(text = "Buy", bounds = NodeBounds(0, 0, 10, 10))
        val result = ElementActions.inspect(Selector.parse("Buy"), listOf(target))
        assertNull(result.target)
        // The gesture fallback still has a point to tap.
        assertNotNull(result.centre)
    }

    @Test
    fun theInspectionNeverThrowsOnAnEmptyTree() {
        val result = ElementActions.inspect(Selector.parse("any"), emptyList())
        assertNull(result.hit)
        assertEquals(0, result.matches)
    }

    @Test
    fun performClickOnANullTargetIsFalseRatherThanACrash() {
        assertTrue(!ElementActions.performClickOnTarget(null))
        assertTrue(!ElementActions.performLongClickOnTarget(null))
    }

    private fun node(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        bounds: NodeBounds? = null,
        clickable: Boolean = false,
        children: List<NodeView> = emptyList()
    ): FakeNode = FakeNode(
        text = text,
        desc = desc,
        resId = resId,
        bounds = bounds,
        clickable = clickable,
        children = children
    )
}
