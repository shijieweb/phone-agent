package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC.md section 3.1: element selectors.
 *
 * [Selector] is deliberately free of Android types — it works over the
 * framework-free [NodeView] — so the whole matching contract is testable on a
 * plain JVM (SPEC.md section 10).
 */
class SelectorTest {

    /** Minimal [NodeView] so selectors can be exercised without a device. */
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

    // ---------------------------------------------------------------- parsing

    @Test
    fun aBareValueMatchesAnyOfTextDescriptionAndResourceId() {
        val selector = Selector.parse("登录")
        assertEquals(Selector.Kind.ANY, selector.kind)
        assertEquals("登录", selector.value)
    }

    @Test
    fun everyDocumentedKindPrefixIsRecognised() {
        assertEquals(Selector.Kind.TEXT, Selector.parse("text:确定").kind)
        assertEquals(Selector.Kind.DESC, Selector.parse("desc:确定").kind)
        assertEquals(Selector.Kind.ID, Selector.parse("id:submit").kind)
        assertEquals(Selector.Kind.CLS, Selector.parse("cls:Button").kind)
    }

    @Test
    fun kindPrefixIsCaseInsensitive() {
        assertEquals(Selector.Kind.TEXT, Selector.parse("TEXT:ok").kind)
        assertEquals(Selector.Kind.ID, Selector.parse("Id:ok").kind)
    }

    @Test
    fun anUnknownPrefixIsPartOfTheValueNotAKind() {
        // "foo:bar" is not a documented kind, so the whole thing is the value and
        // the default (any of text/desc/id) applies.
        val selector = Selector.parse("foo:bar")
        assertEquals(Selector.Kind.ANY, selector.kind)
        assertEquals("foo:bar", selector.value)
    }

    @Test
    fun theValueKeepsEverythingAfterTheFirstColonVerbatim() {
        val selector = Selector.parse("text:a:b:c")
        assertEquals(Selector.Kind.TEXT, selector.kind)
        assertEquals("a:b:c", selector.value)
    }

    @Test
    fun spacesInsideTheValueAreSignificant() {
        // SPEC: the selector is the rest of the line, verbatim, spaces included.
        assertEquals("delete all", Selector.parse("text:delete all").value)
        assertEquals("a  b", Selector.parse("a  b").value)
    }

    @Test
    fun anEmptyValueNeverMatchesAnything() {
        val selector = Selector.parse("")
        assertEquals("", selector.value)
        assertFalse(selector.matches(anyNode()))
    }

    @Test
    fun aTrimmedLineStripsOnlyTheSurroundingWhitespace() {
        assertEquals("确定", Selector.parse("  确定  ").value)
        assertEquals("a b", Selector.parse("  a b  ").value)
    }

    // --------------------------------------------------------------- matching

    @Test
    fun bareValueMatchesText() {
        assertTrue(Selector.parse("登录").matches(node(text = "请先登录")));
    }

    @Test
    fun bareValueMatchesContentDescription() {
        assertTrue(Selector.parse("返回").matches(node(desc = "返回上一级")))
    }

    @Test
    fun bareValueMatchesResourceId() {
        assertTrue(Selector.parse("submit").matches(node(resId = "com.x:id/submit_button")))
    }

    @Test
    fun matchingIsSubstring() {
        assertTrue(Selector.parse("log").matches(node(text = "login now")))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(Selector.parse("LOGIN").matches(node(text = "please login")))
        assertTrue(Selector.parse("login").matches(node(text = "PLEASE LOGIN")))
    }

    @Test
    fun aKindPrefixRestrictsTheField() {
        // `text:foo` must not match on the description alone.
        val onDescOnly = node(desc = "foo bar")
        assertTrue(Selector.parse("foo").matches(onDescOnly))
        assertFalse(Selector.parse("text:foo").matches(onDescOnly))
        assertTrue(Selector.parse("desc:foo").matches(onDescOnly))
    }

    @Test
    fun idKindMatchesAFullyQualifiedResourceIdBySubstring() {
        val node = node(resId = "com.meituan.android:id/foo_bar")
        assertTrue(Selector.parse("id:foo").matches(node))
        assertTrue(Selector.parse("id:com.meituan.android").matches(node))
        assertFalse(Selector.parse("id:nope").matches(node))
    }

    @Test
    fun clsKindMatchesTheClassName() {
        val node = node(className = "android.widget.Button")
        assertTrue(Selector.parse("cls:Button").matches(node))
        assertTrue(Selector.parse("cls:android.widget").matches(node))
        assertFalse(Selector.parse("cls:TextView").matches(node))
    }

    @Test
    fun aNullFieldNeverMatches() {
        assertFalse(Selector.parse("x").matches(node(text = null, desc = null, resId = null)))
    }

    @Test
    fun whenMultipleFieldsMatchTheFirstDocumentedFieldWinsForTheLabel() {
        val node = node(text = "Buy", desc = "buy now", resId = "com.x:id/buy")
        val hit = Selector.parse("buy").find(listOf(node))
        assertEquals(node, hit?.node)
        // Label preference: resId, then text, then desc (SPEC 3.1).
        assertEquals("com.x:id/buy", hit?.label)
    }

    @Test
    fun theLabelFallsBackToTextThenDescription() {
        val textOnly = node(text = "Buy now")
        assertEquals("Buy now", Selector.parse("buy").find(listOf(textOnly))?.label)

        val descOnly = node(desc = "Buy now")
        assertEquals("Buy now", Selector.parse("buy").find(listOf(descOnly))?.label)
    }

    @Test
    fun theLabelIsTruncatedToFortyChars() {
        val long = "a".repeat(80)
        val node = node(text = long)
        // Force a text-only label by having no resId.
        val hit = Selector.parse("aaa").find(listOf(node))
        assertEquals(40, hit?.label?.length)
        assertEquals("a".repeat(40), hit?.label)
    }

    // ------------------------------------------------------------- traversal

    @Test
    fun theWalkIsDepthFirstPreOrder() {
        val first = node(text = "hit")
        val nested = node(text = "hit")
        val child = node(text = "hit", children = listOf(nested))
        val root = node(text = "root", children = listOf(first, child))
        // Pre-order: root's first child before the grandchild.
        val hit = Selector.parse("hit").find(listOf(root))
        assertEquals(first, hit?.node)
    }

    @Test
    fun everyWindowRootIsWalkedInOrder() {
        val inSecond = node(text = "hit")
        val hit = Selector.parse("hit").find(listOf(node(text = "none"), inSecond))
        assertEquals(inSecond, hit?.node)
    }

    @Test
    fun theFirstMatchWins() {
        val first = node(text = "hit one")
        val second = node(text = "hit two")
        assertEquals(first, Selector.parse("hit").find(listOf(first, second))?.node)
    }

    @Test
    fun noMatchYieldsNull() {
        assertNull(Selector.parse("nothing").find(listOf(node(text = "hello"))))
        assertNull(Selector.parse("nothing").find(emptyList()))
    }

    @Test
    fun matchingCountsEveryHitSoTheOperatorCanSeeAmbiguity() {
        val root = node(
            text = "hit",
            children = listOf(node(text = "hit"), node(desc = "nothing"))
        )
        val result = Selector.parse("hit").findAll(listOf(root))
        assertEquals(2, result.size)
    }

    // ------------------------------------------------- clickable ancestry

    @Test
    fun aClickableNodeIsItsOwnActionTarget() {
        val target = node(text = "hit", clickable = true)
        assertTrue(Selector.parse("hit").find(listOf(target))?.clickableTarget != null)
    }

    @Test
    fun theNearestClickableAncestorIsUsedWhenTheHitIsNotClickable() {
        val inner = node(text = "hit")
        val outer = node(clickable = true, children = listOf(inner))
        val hit = Selector.parse("hit").find(listOf(outer))
        assertEquals(inner, hit?.node)
        assertEquals(outer, hit?.clickableTarget)
    }

    @Test
    fun anUnclickableHitWithNoClickableAncestorHasNoActionTarget() {
        val inner = node(text = "hit")
        val outer = node(children = listOf(inner))
        val hit = Selector.parse("hit").find(listOf(outer))
        assertNull(hit?.clickableTarget)
    }

    @Test
    fun theClosestClickableAncestorWinsWhenThereAreSeveral() {
        val inner = node(text = "hit")
        val middle = node(clickable = true, children = listOf(inner))
        val top = node(clickable = true, children = listOf(middle))
        val hit = Selector.parse("hit").find(listOf(top))
        assertEquals(middle, hit?.clickableTarget)
    }

    @Test
    fun theAncestorPathIsRecordedForReporting() {
        val inner = node(text = "hit")
        val outer = node(clickable = true, children = listOf(inner))
        val hit = Selector.parse("hit").find(listOf(outer))
        // `via=ancestor` in the stdout means the click landed on a parent.
        assertEquals(1, hit?.ancestorDepth)
    }

    @Test
    fun aDirectlyClickableHitHasZeroAncestorDepth() {
        val target = node(text = "hit", clickable = true)
        assertEquals(0, Selector.parse("hit").find(listOf(target))?.ancestorDepth)
    }

    private fun anyNode(): NodeView = node(text = "anything")

    private fun node(
        className: String? = null,
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        bounds: NodeBounds? = null,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        focusable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
        selected: Boolean = false,
        visibleToUser: Boolean = true,
        children: List<NodeView> = emptyList()
    ): FakeNode = FakeNode(
        className = className,
        text = text,
        desc = desc,
        resId = resId,
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        focusable = focusable,
        editable = editable,
        scrollable = scrollable,
        checked = checked,
        enabled = enabled,
        selected = selected,
        visibleToUser = visibleToUser,
        children = children
    )
}
