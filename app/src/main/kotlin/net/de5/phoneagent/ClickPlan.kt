package net.de5.phoneagent

/**
 * What `click` decides once `ACTION_CLICK` has already failed **and** the hit has
 * no tap point of its own (SPEC.md section 3.1, v0.8.4-pad12).
 *
 * The `z` flag (SPEC.md section 5) marks a node whose rect is printed but cannot
 * be tapped: `right <= left` or `bottom <= top`, which is exactly the case
 * [ElementActions.centreOf] refuses. Such a node is common — 58 of 72 text nodes
 * on one 糖豆 page, 97 of 163 on a 百度 page — and its clickable container is
 * usually right above it, so "the text is there but I cannot press it" is the
 * normal state of a busy page rather than a rare edge case.
 *
 * ## Why the walk up is not automatic
 *
 * Walking up crosses a semantic boundary. A row of a list is one clickable
 * container; the "删除" label inside it belongs to a different control than the
 * row itself — tapping the row opens the detail page, it does not delete. The
 * container is the only thing with a rect, so it is the only thing a gesture can
 * reach, and reaching it is a *guess about intent*. A wrong guess on a phone
 * nobody is holding is destructive, so the guess is opt-in: without `up`, the
 * answer names the ancestor and stops at `exit=5`; with `up`, the tap happens
 * and the reply says which ancestor it landed on.
 *
 * ## Why the walk also needs geometry (v0.8.5-pad12)
 *
 * Walking up is not enough on a page: a WebView can place a node whose parent chain
 * has nothing to do with where it is drawn. Measured on a 糖豆 page (1200x2670), all
 * 113 text nodes carrying a `z` rect reached the *same* off-screen `Button` at
 * `1060,402,1153,451` — the 注册 button, pushed onto the same line by the layout —
 * and 25 of them would have been tapped there. A parent normally contains its child,
 * so the ancestor that really owns the node always shares pixels with it; the
 * cross-semantic one does not. [overlaps] is that test: the nearest clickable,
 * usable ancestor that shares both a pixel column and a pixel row. It takes the
 * count from 113 hits to 27 on that page, and every one of the 27 lands on the
 * container that really does hold the text.
 *
 * [overlaps] is looser than "fully contains" on purpose: a label that spills a few
 * pixels outside its container is still that container's label, and refusing it
 * would lose real taps for no safety gain.
 *
 * Everything here is pure over [NodeView]: no `Context`, no framework call, so
 * the whole decision is covered by JVM unit tests.
 */
internal object ClickPlan {

    /**
     * True when a rect can be tapped: a missing rect counts as usable (there is
     * nothing misleading to flag — the empty bounds column already says so), and
     * an inverted or zero-area one does not.
     *
     * [UiTree] delegates its `z` letter to this function, so the flag printed by
     * `tree` and the rule `click` follows cannot drift apart.
     */
    fun isUsable(bounds: NodeBounds?): Boolean {
        if (bounds == null) return true
        return bounds.right > bounds.left && bounds.bottom > bounds.top
    }

    /**
     * True when the two rects share at least one pixel column **and** one pixel row.
     *
     * A missing rect on either side answers false: there is no geometry to compare,
     * so the node cannot be shown to belong to the hit. An inverted rect (the `z`
     * case, `right < left`) is normalised to a proper box first — a dead rect still
     * says *where* the node would be, and that is exactly what this rule needs.
     *
     * The comparison is inclusive (`<=`), so a zero-width or zero-height node that
     * only touches its container's edge still counts: `10,2590,36,2590` has to match
     * the bar at `0,2536,1201,2590` that holds it.
     */
    fun overlaps(a: NodeBounds?, b: NodeBounds?): Boolean {
        if (a == null || b == null) return false
        val aLeft = minOf(a.left, a.right)
        val aRight = maxOf(a.left, a.right)
        val aTop = minOf(a.top, a.bottom)
        val aBottom = maxOf(a.top, a.bottom)
        val bLeft = minOf(b.left, b.right)
        val bRight = maxOf(b.left, b.right)
        val bTop = minOf(b.top, b.bottom)
        val bBottom = maxOf(b.top, b.bottom)
        return aLeft <= bRight && bLeft <= aRight && aTop <= bBottom && bTop <= aBottom
    }

    /**
     * The chain of nodes above [node], outermost first and **nearest last**.
     * Empty when [node] is not in the tree (it was re-read or removed between the
     * inspection and this walk).
     */
    fun ancestorsOf(roots: List<NodeView>, node: NodeView): List<NodeView> {
        val path = ArrayList<NodeView>()
        return if (collect(roots, node, path)) path else emptyList()
    }

    private fun collect(nodes: List<NodeView>, node: NodeView, path: MutableList<NodeView>): Boolean {
        for (candidate in nodes) {
            if (candidate === node) return true
            path.add(candidate)
            if (collect(candidate.children, node, path)) return true
            path.removeAt(path.size - 1)
        }
        return false
    }

    /**
     * The nearest ancestor a gesture can actually press: `clickable`, with a usable
     * rect, **and** sharing pixels with [hit] (see [overlaps]). An ancestor that is
     * clickable but has no rect of its own is skipped — it would only move the
     * failure one level up — and so is one that is drawn somewhere else entirely,
     * which is what keeps a WebView's off-screen button from swallowing the tap.
     *
     * [hit] may be null, which means "no geometry to compare": the answer is then the
     * pre-v0.8.5 nearest clickable usable ancestor. `click` always passes the hit.
     */
    fun clickableAncestor(ancestors: List<NodeView>, hit: NodeView? = null): NodeView? {
        for (index in ancestors.indices.reversed()) {
            val candidate = ancestors[index]
            if (!candidate.clickable) continue
            if (!isUsable(candidate.bounds)) continue
            if (hit != null && !overlaps(candidate.bounds, hit.bounds)) continue
            return candidate
        }
        return null
    }

    /** What `click` should do for a hit that has no centre of its own. */
    sealed class Fallback {
        /** Report the old `node has no usable bounds` line, unchanged. */
        class Unchanged(val line: String) : Fallback()

        /** `exit=5`, naming the ancestor and the `up` switch that would press it. */
        class Hint(val line: String) : Fallback()

        /** `up` was given and there is nothing above to press. */
        class NoAncestor(val line: String) : Fallback()

        /** `up` was given: tap the ancestor's centre. */
        class Tap(val ancestor: NodeView, val x: Int, val y: Int) : Fallback()
    }

    /**
     * The single decision behind the no-bounds branch of `click`.
     *
     * A hit whose own rect **is** usable never reaches the ancestor logic, even
     * when [ElementActions.centreOf] still answered null (a rect off the top-left
     * of the screen: negative x/y). That keeps the promise that only a `z` node
     * can produce the new wording.
     */
    fun fallback(
        roots: List<NodeView>,
        hit: NodeView,
        selectorRaw: String,
        matchesText: String,
        up: Boolean
    ): Fallback {
        val legacy = "node has no usable bounds: " + selectorRaw + " " + matchesText
        if (isUsable(hit.bounds)) return Fallback.Unchanged(legacy)
        val ancestor = clickableAncestor(ancestorsOf(roots, hit), hit)
        if (ancestor == null) {
            return if (up) Fallback.NoAncestor("no clickable ancestor for: " + describe(hit))
            else Fallback.Unchanged(legacy)
        }
        if (!up) return Fallback.Hint(hintLine(hit, ancestor))
        val point = ElementActions.centreOf(ancestor.bounds) ?: return Fallback.Unchanged(legacy)
        return Fallback.Tap(ancestor, point[0], point[1])
    }

    /** `<class> "<text-or-desc>" at <l,t,r,b>` — the quoted part is omitted when empty. */
    fun describe(node: NodeView): String {
        val label = textOrDesc(node)
        val quoted = if (label.isEmpty()) "" else " \"" + label + "\""
        return (node.className ?: "?") + quoted + " at " + rectOf(node)
    }

    fun hintLine(hit: NodeView, ancestor: NodeView): String =
        "node has no usable bounds: " + describe(hit) +
            "; nearest clickable ancestor is " + describe(ancestor) +
            " — add `up` to tap it"

    /** `<class> <l,t,r,b>` — the short form used on the success line. */
    fun ancestorToken(ancestor: NodeView): String =
        (ancestor.className ?: "?") + " " + rectOf(ancestor)

    /**
     * `clicked via=ancestor node=<label> at=<x>,<y> ancestor=<class> <l,t,r,b>`.
     *
     * `node=`/`at=` keep their documented meaning (SPEC.md section 3.1,
     * `manualcheck.md`): `at=` is where the tap landed, which for this path is the
     * ancestor's centre — `ancestor=` is appended so the line also says *what* was
     * pressed.
     */
    fun ancestorSuccessLine(label: String, x: Int, y: Int, ancestor: NodeView): String =
        "clicked via=ancestor node=" + label + " at=" + x + "," + y + " ancestor=" + ancestorToken(ancestor)

    private fun rectOf(node: NodeView): String {
        val bounds = node.bounds ?: return "-"
        return bounds.left.toString() + "," + bounds.top + "," + bounds.right + "," + bounds.bottom
    }

    private fun textOrDesc(node: NodeView): String {
        val text = node.text?.trim().orEmpty()
        if (text.isNotEmpty()) return text
        return node.desc?.trim().orEmpty()
    }
}
