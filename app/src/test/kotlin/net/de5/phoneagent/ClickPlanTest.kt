package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ancestor fallback of `click` (SPEC.md section 3.1, v0.8.4-pad12): what
 * happens when the matched node has no tap point of its own.
 *
 * [ClickPlan] is pure over [NodeView], so the whole decision — which ancestor,
 * whether to name it or press it, and the exact wording of every answer — is
 * pinned here without a device.
 *
 * v0.8.5-pad12 adds the geometry rule ([ClickPlan.overlaps]): an ancestor only
 * counts when it shares pixels with the hit. The case that forced it — every label
 * on a 糖豆 page walking up to the same off-screen 注册 button — is pinned here with
 * the real rects from that page.
 */
class ClickPlanTest {

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

    private fun node(
        className: String? = "android.widget.TextView",
        text: String? = null,
        desc: String? = null,
        bounds: NodeBounds? = null,
        clickable: Boolean = false,
        children: List<NodeView> = emptyList()
    ): FakeNode = FakeNode(
        className = className,
        text = text,
        desc = desc,
        bounds = bounds,
        clickable = clickable,
        children = children
    )

    /** The 知乎 rect from SPEC.md section 5: printed, inverted, untappable. */
    private fun zeroArea(text: String): FakeNode = node(text = text, bounds = NodeBounds(52, 254, 1148, 86))

    // ---------------------------------------------------------------- usability

    @Test
    fun aMissingRectIsNotUnusable() {
        // Nothing is printed, so there is nothing misleading to flag: the empty
        // bounds column already says "no rect". Only a printed-but-dead rect is z.
        assertTrue(ClickPlan.isUsable(null))
    }

    @Test
    fun anInvertedOrZeroAreaRectIsUnusable() {
        assertTrue(!ClickPlan.isUsable(NodeBounds(52, 254, 1148, 86)))
        assertTrue(!ClickPlan.isUsable(NodeBounds(10, 10, 10, 40)))
        assertTrue(!ClickPlan.isUsable(NodeBounds(10, 10, 40, 10)))
        assertTrue(ClickPlan.isUsable(NodeBounds(10, 10, 40, 40)))
    }

    // ------------------------------------------------------------ ancestor walk

    @Test
    fun theNearestClickableAncestorWinsOverAGrandparent() {
        val label = zeroArea("删除")
        val row = node(bounds = NodeBounds(0, 100, 200, 200), clickable = true, children = listOf(label))
        val list = node(bounds = NodeBounds(0, 0, 200, 900), clickable = true, children = listOf(row))

        assertEquals(listOf<NodeView>(list, row), ClickPlan.ancestorsOf(listOf(list), label))
        assertEquals(row, ClickPlan.clickableAncestor(ClickPlan.ancestorsOf(listOf(list), label)))
    }

    @Test
    fun aClickableAncestorWithoutAUsableRectIsSkipped() {
        // The nearest clickable container prints a dead rect too (a collapsed
        // wrapper), so pressing it would only move the failure one level up.
        val label = zeroArea("删除")
        val deadRow = node(
            bounds = NodeBounds(0, 100, 0, 200),
            clickable = true,
            children = listOf(label)
        )
        val list = node(bounds = NodeBounds(0, 0, 200, 900), clickable = true, children = listOf(deadRow))

        val plan = ClickPlan.fallback(listOf(list), label, "删除", "matches=1", up = false)
        assertTrue(plan is ClickPlan.Fallback.Hint)
        assertEquals(
            "node has no usable bounds: android.widget.TextView \"删除\" at 52,254,1148,86" +
                "; nearest clickable ancestor is android.widget.TextView at 0,0,200,900" +
                " — add `up` to tap it",
            (plan as ClickPlan.Fallback.Hint).line
        )
    }

    @Test
    fun aNodeThatIsNotInTheTreeHasNoAncestors() {
        val stranger = zeroArea("删除")
        val tree = node(bounds = NodeBounds(0, 0, 10, 10), clickable = true)
        assertTrue(ClickPlan.ancestorsOf(listOf(tree), stranger).isEmpty())
    }

    // ------------------------------------------------------------------ answers

    @Test
    fun noClickableAncestorAnywhereKeepsTheOldLine() {
        val label = zeroArea("删除")
        val plain = node(bounds = NodeBounds(0, 0, 200, 900), children = listOf(label))
        val plan = ClickPlan.fallback(listOf(plain), label, "删除", "matches=1", up = false)
        assertTrue(plan is ClickPlan.Fallback.Unchanged)
        // Byte-identical to v0.8.3: the operator sees exactly what they saw before.
        assertEquals(
            "node has no usable bounds: 删除 matches=1",
            (plan as ClickPlan.Fallback.Unchanged).line
        )
    }

    @Test
    fun theHintNamesTheAncestorAndTheSwitchThatWouldPressIt() {
        val label = zeroArea("删除")
        val row = node(bounds = NodeBounds(0, 100, 200, 200), clickable = true, children = listOf(label))
        val plan = ClickPlan.fallback(listOf(row), label, "删除", "matches=2", up = false)
        assertTrue(plan is ClickPlan.Fallback.Hint)
        assertEquals(
            "node has no usable bounds: android.widget.TextView \"删除\" at 52,254,1148,86" +
                "; nearest clickable ancestor is android.widget.TextView at 0,100,200,200" +
                " — add `up` to tap it",
            (plan as ClickPlan.Fallback.Hint).line
        )
    }

    @Test
    fun theAncestorDescriptorUsesTheDescriptionWhenThereIsNoText() {
        val label = node(desc = "更多操作", bounds = NodeBounds(5, 5, 5, 5))
        assertEquals("android.widget.TextView \"更多操作\" at 5,5,5,5", ClickPlan.describe(label))
    }

    @Test
    fun anAncestorWithNoLabelIsDescribedByItsRectOnly() {
        val ancestor = node(className = "android.widget.LinearLayout", bounds = NodeBounds(0, 100, 200, 200))
        assertEquals("android.widget.LinearLayout at 0,100,200,200", ClickPlan.describe(ancestor))
        assertEquals("android.widget.LinearLayout 0,100,200,200", ClickPlan.ancestorToken(ancestor))
    }

    @Test
    fun upWithNoAncestorNamesTheHitInsteadOfTapping() {
        val label = zeroArea("删除")
        val plan = ClickPlan.fallback(listOf(label), label, "删除", "matches=1", up = true)
        assertTrue(plan is ClickPlan.Fallback.NoAncestor)
        assertEquals(
            "no clickable ancestor for: android.widget.TextView \"删除\" at 52,254,1148,86",
            (plan as ClickPlan.Fallback.NoAncestor).line
        )
    }

    @Test
    fun upTapsTheAncestorCentre() {
        val label = zeroArea("删除")
        val row = node(bounds = NodeBounds(0, 100, 200, 200), clickable = true, children = listOf(label))
        val plan = ClickPlan.fallback(listOf(row), label, "删除", "matches=1", up = true)
        assertTrue(plan is ClickPlan.Fallback.Tap)
        val tap = plan as ClickPlan.Fallback.Tap
        assertEquals(row, tap.ancestor)
        assertEquals(100, tap.x)
        assertEquals(150, tap.y)
    }

    @Test
    fun aHitWithAUsableRectNeverEntersTheAncestorLogic() {
        // The default path: the node has a rect of its own, so the gesture branch
        // taps it exactly as before — even though a clickable ancestor exists and
        // `up` was asked for. `up` is only about nodes that cannot be tapped.
        val label = node(text = "删除", bounds = NodeBounds(10, 110, 60, 140))
        val row = node(bounds = NodeBounds(0, 100, 200, 200), clickable = true, children = listOf(label))
        val plan = ClickPlan.fallback(listOf(row), label, "删除", "matches=1", up = true)
        assertTrue(plan is ClickPlan.Fallback.Unchanged)
        assertEquals(
            "node has no usable bounds: 删除 matches=1",
            (plan as ClickPlan.Fallback.Unchanged).line
        )
    }

    @Test
    fun theSuccessLineKeepsTheDocumentedTailAndAddsTheAncestor() {
        val row = node(className = "android.widget.LinearLayout", bounds = NodeBounds(0, 100, 200, 200), clickable = true)
        assertEquals(
            "clicked via=ancestor node=删除 at=100,150 ancestor=android.widget.LinearLayout 0,100,200,200",
            ClickPlan.ancestorSuccessLine("删除", 100, 150, row)
        )
    }

    // ------------------------------------------------------------ `up` parsing

    @Test
    fun upIsALeadingSwitchInAnyOrderWithApply() {
        assertEquals(
            Command.Click(Selector.parse("登录"), true, true),
            Commands.parse("click up apply 登录")
        )
        assertEquals(
            Command.Click(Selector.parse("登录"), true, true),
            Commands.parse("click apply up 登录")
        )
        assertEquals(
            Command.Click(Selector.parse("登录"), false, true),
            Commands.parse("click up 登录")
        )
        // The selector still keeps its inner spacing.
        assertEquals(
            Command.Click(Selector.parse("text:delete all"), false, true),
            Commands.parse("click up text:delete all")
        )
    }

    @Test
    fun aSelectorThatStartsWithUpStaysReachableThroughAKindPrefix() {
        assertEquals(
            Command.Click(Selector.parse("text:up"), false, false),
            Commands.parse("click text:up")
        )
        // `click up` has always meant "the node labelled up": with no selector left
        // after it, `up` stays part of the selector. (With something after it, the
        // switch wins — exactly like `apply` — so a label that genuinely starts with
        // the word needs the kind prefix.)
        assertEquals(Command.Click(Selector.parse("up"), false, false), Commands.parse("click up"))
        assertEquals(
            Command.Click(Selector.parse("arrow"), false, true),
            Commands.parse("click up arrow")
        )
        assertEquals(
            Command.Click(Selector.parse("text:up arrow"), false, false),
            Commands.parse("click text:up arrow")
        )
    }

    @Test
    fun upIsClickOnlyAndDoesNotLeakIntoLongclick() {
        assertEquals(
            Command.LongClick(Selector.parse("up 登录"), false),
            Commands.parse("longclick up 登录")
        )
    }

    @Test
    fun aLongclickWithoutAUsableRectStillAnswersTheOldLine() {
        // longclick has no ancestor fallback at all (SPEC.md section 3.1 only gives
        // click the `up` switch), so the pure plan is what pins that.
        val label = zeroArea("删除")
        val row = node(bounds = NodeBounds(0, 100, 200, 200), clickable = true, children = listOf(label))
        val plan = ClickPlan.fallback(listOf(row), label, "删除", "matches=1", up = false)
        assertTrue(plan is ClickPlan.Fallback.Hint)
        assertNull(ElementActions.inspect(Selector.parse("nothing"), listOf(row)).hit)
    }

    // ------------------------------------------------------- geometry (v0.8.5)

    @Test
    fun overlapsNeedsBothAxesAndAMissingRectAnswersNo() {
        // No rect on either side: there is no geometry to compare, so nothing can be
        // shown to belong to the hit.
        assertTrue(!ClickPlan.overlaps(null, NodeBounds(0, 0, 10, 10)))
        assertTrue(!ClickPlan.overlaps(NodeBounds(0, 0, 10, 10), null))
        assertTrue(!ClickPlan.overlaps(null, null))
        // Sharing a column is not enough: the rows have to meet as well.
        assertTrue(!ClickPlan.overlaps(NodeBounds(0, 0, 100, 100), NodeBounds(0, 200, 100, 300)))
        assertTrue(ClickPlan.overlaps(NodeBounds(0, 0, 100, 100), NodeBounds(50, 50, 150, 150)))
        // The 糖豆 rects: the 注册 button is drawn on a completely different line.
        assertTrue(!ClickPlan.overlaps(NodeBounds(120, 2590, 529, 2590), NodeBounds(1060, 402, 1153, 451)))
        // An inverted (`z`) rect still says *where* the node is, so it is normalised.
        assertTrue(ClickPlan.overlaps(NodeBounds(52, 254, 1148, 86), NodeBounds(0, 0, 1201, 2670)))
        assertTrue(!ClickPlan.overlaps(NodeBounds(52, 254, 1148, 86), NodeBounds(0, 300, 1201, 400)))
    }

    @Test
    fun overlapsCountsAnEdgeThatOnlyTouches() {
        // Inclusive on purpose: the 静音 button is 10,2590,36,2590 — zero height,
        // sitting exactly on the bottom edge of the bar that holds it.
        assertTrue(ClickPlan.overlaps(NodeBounds(10, 2590, 36, 2590), NodeBounds(0, 2536, 1201, 2590)))
        assertTrue(ClickPlan.overlaps(NodeBounds(0, 2536, 1201, 2590), NodeBounds(10, 2590, 36, 2590)))
    }

    @Test
    fun aCrossSemanticAncestorIsNoLongerAHomeForTheTap() {
        // 拍舞曲同款 on the 糖豆 page: the nearest clickable ancestor is the 注册
        // button, pushed onto the same line by the WebView. v0.8.4 would have named
        // it as the target; the geometry says it is not where the label is drawn.
        val label = node(text = "拍舞曲同款", bounds = NodeBounds(120, 2590, 529, 2590))
        val signUp = node(
            className = "android.widget.Button",
            text = "注册",
            bounds = NodeBounds(1060, 402, 1153, 451),
            clickable = true,
            children = listOf(label)
        )
        val plan = ClickPlan.fallback(listOf(signUp), label, "拍舞曲同款", "matches=1", up = false)
        assertTrue(plan is ClickPlan.Fallback.Unchanged)
        assertEquals(
            "node has no usable bounds: 拍舞曲同款 matches=1",
            (plan as ClickPlan.Fallback.Unchanged).line
        )
    }

    @Test
    fun aZeroWidthNodeStillBelongsToTheWideBarThatHoldsIt() {
        // 静音: zero height, inside the full-width player bar. The tap lands on the
        // bar — which is what a finger aiming at the label would have hit anyway.
        val mute = node(className = "android.widget.Button", desc = "静音", bounds = NodeBounds(10, 2590, 36, 2590))
        val bar = node(
            className = "android.view.View",
            bounds = NodeBounds(0, 2536, 1201, 2590),
            clickable = true,
            children = listOf(mute)
        )
        val hint = ClickPlan.fallback(listOf(bar), mute, "静音", "matches=1", up = false)
        assertTrue(hint is ClickPlan.Fallback.Hint)
        assertEquals(
            "node has no usable bounds: android.widget.Button \"静音\" at 10,2590,36,2590" +
                "; nearest clickable ancestor is android.view.View at 0,2536,1201,2590" +
                " — add `up` to tap it",
            (hint as ClickPlan.Fallback.Hint).line
        )
        val tap = ClickPlan.fallback(listOf(bar), mute, "静音", "matches=1", up = true)
        assertTrue(tap is ClickPlan.Fallback.Tap)
        val pressed = tap as ClickPlan.Fallback.Tap
        assertEquals(bar, pressed.ancestor)
        assertEquals(600, pressed.x)
        assertEquals(2563, pressed.y)
    }

    @Test
    fun anAncestorWithNoRectOfItsOwnIsNotACandidate() {
        // isUsable(null) is true — a missing rect is not misleading — so the geometry
        // check is the only thing keeping a rect-less wrapper from being named.
        val label = zeroArea("删除")
        val wrapper = node(className = "android.view.View", bounds = null, clickable = true, children = listOf(label))
        val plan = ClickPlan.fallback(listOf(wrapper), label, "删除", "matches=1", up = false)
        assertTrue(plan is ClickPlan.Fallback.Unchanged)
        assertEquals(
            "node has no usable bounds: 删除 matches=1",
            (plan as ClickPlan.Fallback.Unchanged).line
        )
        assertNull(ClickPlan.clickableAncestor(ClickPlan.ancestorsOf(listOf(wrapper), label), label))
    }

    @Test
    fun upWithOnlyCrossSemanticAncestorsNamesTheHitInsteadOfTapping() {
        val label = node(text = "达人老师", bounds = NodeBounds(0, 2590, 1201, 2590))
        val signUp = node(
            className = "android.widget.Button",
            text = "注册",
            bounds = NodeBounds(1060, 402, 1153, 451),
            clickable = true,
            children = listOf(label)
        )
        val plan = ClickPlan.fallback(listOf(signUp), label, "达人老师", "matches=1", up = true)
        assertTrue(plan is ClickPlan.Fallback.NoAncestor)
        assertEquals(
            "no clickable ancestor for: android.widget.TextView \"达人老师\" at 0,2590,1201,2590",
            (plan as ClickPlan.Fallback.NoAncestor).line
        )
    }

    @Test
    fun theWalkSkipsAnAncestorDrawnElsewhereAndTakesTheOneAbove() {
        // Nearest clickable ancestor is the 注册 button; the one above it is the
        // player bar that really holds the label. Only the bar overlaps the hit.
        val label = node(text = "拍舞曲同款", bounds = NodeBounds(120, 2590, 529, 2590))
        val signUp = node(
            className = "android.widget.Button",
            text = "注册",
            bounds = NodeBounds(1060, 402, 1153, 451),
            clickable = true,
            children = listOf(label)
        )
        val bar = node(
            className = "android.view.View",
            bounds = NodeBounds(0, 2536, 1201, 2590),
            clickable = true,
            children = listOf(signUp)
        )
        val tap = ClickPlan.fallback(listOf(bar), label, "拍舞曲同款", "matches=1", up = true)
        assertTrue(tap is ClickPlan.Fallback.Tap)
        val pressed = tap as ClickPlan.Fallback.Tap
        assertEquals(bar, pressed.ancestor)
        assertEquals(600, pressed.x)
        assertEquals(2563, pressed.y)
    }

    @Test
    fun aWalkWithoutAHitKeepsTheOldNearestAnswer() {
        // `click` always passes the hit; the null form is the pre-v0.8.5 rule, kept
        // for a caller that only has the ancestor chain.
        val label = zeroArea("删除")
        val row = node(bounds = NodeBounds(0, 100, 200, 200), clickable = true, children = listOf(label))
        assertEquals(row, ClickPlan.clickableAncestor(ClickPlan.ancestorsOf(listOf(row), label)))
    }
}
