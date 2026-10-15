package net.de5.phoneagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Element-level actions: `click`, `longclick`, `scroll`, `ime enter`
 * (SPEC.md section 3.1).
 *
 * The *decision* logic lives in [Selector] and is pure; this file performs the
 * framework calls, in the order the spec documents, reporting which path won.
 *
 * ## Threading
 *
 * Two different rules apply and they conflict, so the split matters:
 *
 *  - Reading `AccessibilityNodeInfo` and calling `performAction` **must** happen
 *    on the main thread ([PhoneAccessibilityService] is only valid there).
 *  - Injecting a gesture goes through [Gestures], which itself posts to the main
 *    thread and then *blocks the caller* until the callback fires. Calling it
 *    from the main thread would deadlock the looper that has to run the post.
 *
 * So the caller (the poll thread) passes [onMain] to run framework reads, and any
 * gesture fallback is dispatched from the caller's own thread.
 */
internal object ElementActions {

    /**
     * Runs [block] on the main thread and returns its result, or `null` when it
     * timed out. Injected so this file never has to decide *how* to reach the
     * main thread, and so the click path can dispatch its gesture fallback from
     * the caller's thread (see the threading note above).
     */
    interface MainDispatch {
        fun <T> run(block: () -> T): T?
    }

    /** The text of SPEC.md section 3.1 for a miss: `<SEL>` plus the match count. */
    private fun noMatch(selector: Selector, matches: Int): ExecutionResult =
        ExecutionResult(EXIT_NOT_FOUND, "", "no node matched: ${selector.raw} matches=$matches")

    /**
     * `click SEL`.
     *
     * Attempt order (SPEC.md section 3.1):
     *  1. `ACTION_CLICK` on the hit, or on its nearest clickable ancestor;
     *  2. if that returns false, a gesture tap at the centre of the hit's bounds.
     */
    fun click(
        service: AccessibilityService,
        selector: Selector,
        onMain: MainDispatch
    ): ExecutionResult {
        val read = onMain.run { inspectNow(selector, service) }
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "click timed out")
        if (read.hit == null) return noMatch(selector, 0)

        // Attempt 1: the accessibility action, on the resolved target.
        val actionWon = onMain.run { performClickOnTarget(read.target) } ?: false
        if (actionWon) return success("clicked", read, "action")

        // Attempt 2: a gesture tap at the centre of the matched node's bounds.
        val centre = read.centre
            ?: return ExecutionResult(
                EXIT_NOT_FOUND, "", "node has no usable bounds: ${selector.raw} ${read.matchesText}"
            )
        return if (Gestures.tap(service, centre[0], centre[1])) {
            success("clicked", read, "gesture")
        } else {
            ExecutionResult(EXIT_TIMEOUT, "", "gesture did not complete ${read.matchesText}")
        }
    }

    /** `longclick SEL`: `ACTION_LONG_CLICK` first, then a gesture long press. */
    fun longClick(
        service: AccessibilityService,
        selector: Selector,
        onMain: MainDispatch,
        ms: Int = LONG_PRESS_MS
    ): ExecutionResult {
        val read = onMain.run { inspectNow(selector, service) }
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "longclick timed out")
        if (read.hit == null) return noMatch(selector, 0)

        val actionWon = onMain.run { performLongClickOnTarget(read.target) } ?: false
        if (actionWon) return success("longclicked", read, "action")

        val centre = read.centre
            ?: return ExecutionResult(
                EXIT_NOT_FOUND, "", "node has no usable bounds: ${selector.raw} ${read.matchesText}"
            )
        return if (Gestures.longPress(service, centre[0], centre[1], ms)) {
            success("longclicked", read, "gesture")
        } else {
            ExecutionResult(EXIT_TIMEOUT, "", "gesture did not complete ${read.matchesText}")
        }
    }

    /**
     * Everything a click/longclick needs, resolved on the main thread: the hit,
     * its action target, where to tap and how many nodes matched.
     */
    internal class Inspection(
        val hit: SelectorHit?,
        val target: NodeView?,
        val centre: IntArray?,
        val matches: Int
    ) {
        val matchesText: String get() = "matches=$matches"
    }

    /**
     * Read-only pass, safe to run on the main thread: walks the tree, resolves
     * the clickable ancestor, brings the node into view when the spec asks for
     * it, and computes the future tap point.
     */
    internal fun inspect(selector: Selector, roots: List<NodeView>): Inspection {
        val hits = selector.findAll(roots)
        val hit = hits.firstOrNull() ?: return Inspection(null, null, null, 0)
        val target = resolveTarget(hit)
        val bounds = (target ?: hit.node).bounds
        return Inspection(hit, target, centreOf(bounds), hits.size)
    }

    /**
     * Convenience for callers that just need "the whole tree as it is now".
     * Must run on the main thread — it reads the accessibility service.
     */
    fun inspectNow(selector: Selector, service: AccessibilityService): Inspection {
        val roots = (service as? PhoneAccessibilityService)?.roots().orEmpty()
        return inspect(selector, roots)
    }

    /**
     * `ACTION_CLICK` on the target. Returns false when there is no target or the
     * framework refuses, which is the signal to fall back to a gesture.
     */
    internal fun performClickOnTarget(target: NodeView?): Boolean {
        if (target == null) return false
        val node = frameworkNodeOf(target) ?: return false
        return performClick(node)
    }

    internal fun performLongClickOnTarget(target: NodeView?): Boolean {
        if (target == null) return false
        val node = frameworkNodeOf(target) ?: return false
        return performLongClick(node)
    }

    /**
     * `scroll up|down [n] [SEL]`.
     *
     * The container to scroll is the nearest scrollable node at or below the
     * selector's match; without a selector it is the first scrollable node of the
     * tree. No scrollable node at all is `exit=5` (SPEC.md section 3.1).
     */
    fun scroll(
        service: AccessibilityService,
        command: Command.Scroll,
        roots: List<NodeView>
    ): ExecutionResult {
        val container = resolveScrollContainer(command.selector, roots)
            ?: return ExecutionResult(EXIT_NOT_FOUND, "", "no scrollable node")

        val action = if (command.direction == Direction.DOWN) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        val node = frameworkNodeOf(container)
            ?: return ExecutionResult(EXIT_NOT_FOUND, "", "no scrollable node")

        val moved = performScroll(node, action, command.count)
        if (!moved) {
            // A scrollable node that refuses the action is still "not found" from
            // the operator's point of view: nothing moved.
            return ExecutionResult(
                EXIT_NOT_FOUND, "", "scroll not accepted by node=${Selector.label(container)}"
            )
        }

        val word = if (command.direction == Direction.DOWN) "down" else "up"
        return ExecutionResult(
            EXIT_OK,
            "scrolled $word x${command.count} node=${Selector.label(container)}",
            ""
        )
    }

    /**
     * `ime enter`: `ACTION_IME_ENTER` on the focused editable node.
     *
     * `exit=5` when there is no editable field at all (SPEC.md exit-code table:
     * "not found (package, node, focus target)").
     */
    fun imeEnter(roots: List<NodeView>): ExecutionResult {
        val focused = findEditable(roots)
            ?: return ExecutionResult(EXIT_NOT_FOUND, "", "no editable text field")

        val node = frameworkNodeOf(focused)
            ?: return ExecutionResult(EXIT_NOT_FOUND, "", "no editable text field")

        val accepted = try {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } catch (t: Throwable) {
            false
        }
        return if (accepted) {
            ExecutionResult(EXIT_OK, "ime enter", "")
        } else {
            ExecutionResult(EXIT_NOT_FOUND, "", "ime enter not accepted")
        }
    }

    // ------------------------------------------------------------- internals

    private const val LONG_PRESS_MS = 600

    /**
     * The node the action should land on: the hit when it is already clickable,
     * otherwise the nearest clickable ancestor found by [Selector].
     *
     * When the hit is off-screen the spec asks for `ACTION_SHOW_ON_SCREEN`
     * first, so the reported coordinates come from the scrolled-to position.
     */
    private fun resolveTarget(hit: SelectorHit): NodeView? {
        val target = hit.clickableTarget ?: return null
        val node = frameworkNodeOf(target) ?: return target
        if (!target.visibleToUser) {
            try {
                node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                )
            } catch (t: Throwable) {
                // Best effort: the bounds below are simply the stale ones.
            }
        }
        return target
    }

    /**
     * The success line of SPEC.md section 3.1:
     * `clicked via=action|ancestor|gesture node=<resId|text> at=<x>,<y>`.
     *
     * [path] is what actually happened: `action` when the accessibility action
     * landed on the hit itself, `ancestor` when it landed on a clickable parent,
     * `gesture` when the coordinate fallback had to be used.
     */
    private fun success(verb: String, read: Inspection, path: String): ExecutionResult {
        val hit = read.hit ?: return ExecutionResult(EXIT_NOT_FOUND, "", "no node matched")
        val via = when (path) {
            "gesture" -> "gesture"
            else -> if (hit.ancestorDepth > 0) "ancestor" else "action"
        }
        val centre = read.centre
        val at = if (centre == null) "?" else "${centre[0]},${centre[1]}"
        // The label is the *matched* node's, which is what the operator searched
        // for; the target only tells us where the click actually landed.
        return ExecutionResult(
            EXIT_OK,
            "$verb via=$via node=${hit.label} at=$at",
            read.matchesText
        )
    }

    /** Both `click` and `longclick` resolve their scroll container the same way. */
    private fun resolveScrollContainer(selector: Selector?, roots: List<NodeView>): NodeView? {
        if (selector == null) return ScrollTarget.first(roots)
        val hit = selector.find(roots) ?: return null
        // Prefer a scrollable node in the matched subtree, then walk up: the
        // selector often names a list item inside the actual scroller.
        val below = ScrollTarget.at(hit.node)
        if (below != null) return below
        if (hit.node.scrollable) return hit.node
        return ScrollTarget.first(roots)
    }

    /**
     * Repeat the scroll action [count] times, but stop early when the framework
     * says it cannot scroll any further (the list is at its end).
     *
     * @return true when at least one step was accepted.
     */
    private fun performScroll(node: AccessibilityNodeInfo, action: Int, count: Int): Boolean {
        var moved = false
        var i = 0
        while (i < count) {
            val accepted = try {
                node.performAction(action)
            } catch (t: Throwable) {
                false
            }
            if (!accepted) break
            moved = true
            i++
        }
        return moved
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean = try {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } catch (t: Throwable) {
        false
    }

    private fun performLongClick(node: AccessibilityNodeInfo): Boolean = try {
        node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    } catch (t: Throwable) {
        false
    }

    /** Prefers the focused editable node, then any editable node. */
    private fun findEditable(roots: List<NodeView>): NodeView? {
        for (root in roots) {
            val focused = firstEditable(root, true)
            if (focused != null) return focused
        }
        for (root in roots) {
            val any = firstEditable(root, false)
            if (any != null) return any
        }
        return null
    }

    private fun firstEditable(node: NodeView, requireFocused: Boolean): NodeView? {
        if (node.editable && (!requireFocused || node.focusable)) return node
        val children = node.children
        var i = 0
        while (i < children.size) {
            val hit = firstEditable(children[i], requireFocused)
            if (hit != null) return hit
            i++
        }
        return null
    }

    /**
     * The centre of a node's bounds, or null when it is empty/off-screen. A
     * zero-area rect cannot be tapped meaningfully.
     */
    internal fun centreOf(bounds: NodeBounds?): IntArray? {
        if (bounds == null) return null
        val x = (bounds.left + bounds.right) / 2
        val y = (bounds.top + bounds.bottom) / 2
        if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) return null
        if (x < 0 || y < 0) return null
        return intArrayOf(x, y)
    }

    private fun frameworkNodeOf(view: NodeView): AccessibilityNodeInfo? {
        val adapter = view as? AccessibilityNodeAdapter ?: return null
        return try {
            adapter.frameworkNode()
        } catch (t: Throwable) {
            null
        }
    }
}
