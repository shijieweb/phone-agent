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
/**
 * Packages whose buttons `confirm` prefers when a label is ambiguous.
 *
 * The word "安装" exists in half the apps on the phone; the one we want is the one
 * the system installer just drew on top of whatever was underneath.
 */
private val INSTALLER_PACKAGES = listOf(
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.android.permissioncontroller"
)

private fun isInstallerPackage(pkg: String?): Boolean {
    if (pkg == null) return false
    for (candidate in INSTALLER_PACKAGES) {
        if (pkg.equals(candidate, ignoreCase = true)) return true
    }
    return false
}

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
     * `click [apply] [up] SEL`.
     *
     * Attempt order (SPEC.md section 3.1):
     *  1. `ACTION_CLICK` on the hit, or on its nearest clickable ancestor;
     *  2. if that returns false, a gesture tap at the centre of the hit's bounds.
     *
     * ## A hit with no tap point (v0.8.4-pad12)
     *
     * A `z` node (SPEC.md section 5) prints a rect that cannot be tapped, so step 2
     * has no point to use. Its clickable container is usually right above it and
     * [ClickPlan] finds it — but the tap does **not** happen on its own: walking up
     * crosses a semantic boundary (the "删除" label inside a whole-row container —
     * tapping the row opens the detail page, it does not delete), so pressing the
     * ancestor unasked is a guess about intent. Without `up` the answer stays
     * `exit=5` and only *names* that ancestor; with `up` the tap happens and the
     * reply says which ancestor was pressed.
     *
     * Nothing else changes: a hit with a centre of its own — including the
     * zero-area text nodes that make up most of a busy page — still takes exactly
     * the gesture path it always took.
     */
    fun click(
        service: AccessibilityService,
        selector: Selector,
        onMain: MainDispatch,
        apply: Boolean = false,
        up: Boolean = false
    ): ExecutionResult {
        // One main-thread read. The roots are kept because the ancestor walk has to
        // see the very same NodeView instances the selector matched: a second read
        // hands back fresh adapters and the identity search would miss.
        val snap = onMain.run { snapshotNow(selector, service) }
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "click timed out")
        val read = snap.read
        if (!apply) return dryRun("click", selector.raw, read)
        val hit = read.hit ?: return noMatch(selector, 0)

        // Attempt 1: the accessibility action, on the resolved target.
        val actionWon = onMain.run { performClickOnTarget(read.target) } ?: false
        if (actionWon) return success("clicked", read, "action")

        // Attempt 2: a gesture tap at the centre of the matched node's bounds.
        val centre = read.centre ?: return noTapPoint(service, selector, read, hit, snap.roots, up)
        return if (Gestures.tap(service, centre[0], centre[1])) {
            success("clicked", read, "gesture")
        } else {
            ExecutionResult(EXIT_TIMEOUT, "", "gesture did not complete ${read.matchesText}")
        }
    }

    /**
     * The old `node has no usable bounds` answer, now routed through [ClickPlan]:
     * byte-identical when there is no ancestor to name, a hint naming one when
     * there is, and the ancestor tap when the caller asked for it with `up`.
     */
    private fun noTapPoint(
        service: AccessibilityService,
        selector: Selector,
        read: Inspection,
        hit: SelectorHit,
        roots: List<NodeView>,
        up: Boolean
    ): ExecutionResult {
        val plan = ClickPlan.fallback(roots, hit.node, selector.raw, read.matchesText, up)
        return when (plan) {
            is ClickPlan.Fallback.Unchanged -> ExecutionResult(EXIT_NOT_FOUND, "", plan.line)
            is ClickPlan.Fallback.Hint -> ExecutionResult(EXIT_NOT_FOUND, "", plan.line)
            is ClickPlan.Fallback.NoAncestor -> ExecutionResult(EXIT_NOT_FOUND, "", plan.line)
            is ClickPlan.Fallback.Tap -> if (Gestures.tap(service, plan.x, plan.y)) {
                ExecutionResult(
                    EXIT_OK,
                    ClickPlan.ancestorSuccessLine(hit.label, plan.x, plan.y, plan.ancestor),
                    read.matchesText
                )
            } else {
                ExecutionResult(EXIT_TIMEOUT, "", "gesture did not complete ${read.matchesText}")
            }
        }
    }

    /** `longclick SEL`: `ACTION_LONG_CLICK` first, then a gesture long press. */
    fun longClick(
        service: AccessibilityService,
        selector: Selector,
        onMain: MainDispatch,
        ms: Int = LONG_PRESS_MS,
        apply: Boolean = false
    ): ExecutionResult {
        val read = onMain.run { inspectNow(selector, service) }
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "longclick timed out")
        if (!apply) return dryRun("longclick", selector.raw, read)
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
     * `confirm [t=SECONDS] TEXT` — press the button with that label, waiting for
     * it to appear.
     *
     * The pair `install <url>` + `confirm 安装` is how an APK gets installed on a
     * phone nobody is holding: the system installer is an ordinary activity, its
     * buttons are ordinary nodes, and the only thing between us and a hands-free
     * install is the wait — the installer UI takes a variable moment to inflate,
     * so a single look fails at random.
     *
     * ## What counts as a hit
     *
     * Text alone is **not** a hit. A first version matched any node whose text
     * contained the needle, so `confirm zzqq_no_such_button` "succeeded" by
     * tapping our own status line — a plain `TextView` that merely contained the
     * word. A candidate must be *pressable*:
     *
     *  - `clickable`, or
     *  - it has a clickable ancestor (the label inside a list row), or
     *  - its class looks like a button (`android.widget.Button`, `…MaterialButton`),
     *    which is the case where the click has to be a gesture at its centre.
     *
     * A label match that is none of these is counted as *inert* and reported as
     * such instead of being clicked: a wrong tap on an unknown screen is far worse
     * than an honest timeout.
     *
     * ## Which hit wins
     *
     * When several pressure candidates carry the label, the system installer's own
     * wins (`com.android.packageinstaller`, `com.google.android.packageinstaller`,
     * `com.android.permissioncontroller`). The label "安装" exists in half the
     * apps on the phone; the one we want is the one the installer just drew on
     * top of the app underneath it.
     *
     * Runs on the caller's (poll) thread and sleeps between attempts, so it must
     * never be called from the main thread.
     */
    fun confirm(
        service: AccessibilityService,
        text: String,
        timeoutSeconds: Int,
        onMain: MainDispatch,
        apply: Boolean = false
    ): ExecutionResult {
        if (!apply) return dryRunConfirm(service, text, onMain)
        val needle = text.lowercase()
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        var attempts = 0
        var lastInert = 0
        while (true) {
            attempts++
            val scan = onMain.run { scanLabel(needle, service) }
                ?: return ExecutionResult(EXIT_TIMEOUT, "", "confirm timed out reading the tree")
            lastInert = scan.inertMatches
            val read = scan.first
            if (read != null) {
                val actionWon = onMain.run { performClickOnTarget(read.target) } ?: false
                if (actionWon) return confirmed(read, attempts, "action", text)
                val centre = read.centre
                if (centre != null && Gestures.tap(service, centre[0], centre[1])) {
                    return confirmed(read, attempts, "gesture", text)
                }
                // The node is there but refused both paths. Retrying the same node
                // for the whole timeout would only burn the operator's time.
                return ExecutionResult(
                    EXIT_TIMEOUT,
                    "",
                    "找到了「" + text + "」但点击无效 node=" + (read.hit?.label ?: "?")
                )
            }
            if (System.nanoTime() >= deadline) break
            try {
                Thread.sleep(CONFIRM_POLL_MS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return ExecutionResult(EXIT_INTERRUPTED, "", "confirm interrupted")
            }
        }
        // Two very different failures, and the operator needs to tell them apart:
        // "your word never showed up" is a script bug, "your word is only plain
        // text" means the screen is there but has no button with that label.
        if (lastInert > 0) {
            return ExecutionResult(
                EXIT_NOT_FOUND,
                "",
                "confirm 超时：" + timeoutSeconds + "s 内「" + text + "」只匹配到不可点文本（" +
                    lastInert + " 个），没有可按的按钮"
            )
        }
        return ExecutionResult(
            EXIT_NOT_FOUND,
            "",
            "confirm 超时：" + timeoutSeconds + "s 内没有出现含「" + text + "」的节点（尝试 " +
                attempts + " 次）"
        )
    }

    /**
     * Dry-run for `click`/`longclick`: report what *would* be pressed, press
     * nothing. On a phone nobody is holding, one stray tap can dismiss the very
     * dialog the script was about to confirm, so the tap is opt-in (`apply`).
     */
    private fun dryRun(verb: String, text: String, read: Inspection): ExecutionResult {
        if (read.hit == null) return missLine(text)
        return ExecutionResult(EXIT_OK, dryRunLine(verb, text, read), read.matchesText)
    }

    /**
     * Dry-run for `confirm`: one look, no polling. Polling until a timeout only
     * makes sense when a tap is actually going to happen afterwards.
     */
    private fun dryRunConfirm(
        service: AccessibilityService,
        text: String,
        onMain: MainDispatch
    ): ExecutionResult {
        val scan = onMain.run { scanLabel(text.lowercase(), service) }
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "confirm timed out reading the tree")
        val read = scan.first ?: return inertOrMiss(text, scan.inertMatches)
        return ExecutionResult(EXIT_OK, dryRunLine("confirm", text, read), read.matchesText)
    }

    /** The one line a dry run prints, for both the selector and the label verbs. */
    internal fun dryRunLine(verb: String, text: String, read: Inspection): String {
        val hit = read.hit
        val target = read.target
        val via = when {
            target == null -> "gesture"
            (hit?.ancestorDepth ?: 0) > 0 -> "ancestor"
            else -> "action"
        }
        val centre = read.centre
        val at = if (centre == null) "?" else centre[0].toString() + "," + centre[1]
        val pkg = hit?.node?.packageName ?: target?.packageName ?: "?"
        return "dry-run: would " + verb + " \"" + text + "\" via=" + via + " pkg=" + pkg +
            " node=" + (hit?.label ?: "?") + " at=" + at
    }

    /** "nothing matched" for the selector verbs: the word itself never appeared. */
    private fun missLine(text: String): ExecutionResult =
        ExecutionResult(EXIT_NOT_FOUND, "", "没有出现含「" + text + "」的节点")

    /**
     * The same two-way split the confirm timeout uses, so a dry run and a real
     * run explain a failure with identical words.
     */
    private fun inertOrMiss(text: String, inertMatches: Int): ExecutionResult =
        if (inertMatches > 0) {
            ExecutionResult(
                EXIT_NOT_FOUND,
                "",
                "只匹配到不可点文本（" + inertMatches + " 个），没有可按的按钮"
            )
        } else {
            missLine(text)
        }

    private fun confirmed(
        read: Inspection,
        attempts: Int,
        path: String,
        text: String
    ): ExecutionResult {
        val hit = read.hit
        val via = if (path == "gesture") {
            "gesture"
        } else {
            if ((hit?.ancestorDepth ?: 0) > 0) "ancestor" else "action"
        }
        val centre = read.centre
        val at = if (centre == null) "?" else centre[0].toString() + "," + centre[1]
        val pkg = hit?.node?.packageName ?: read.target?.packageName ?: "?"
        return ExecutionResult(
            EXIT_OK,
            "confirmed \"" + text + "\" via=" + via + " pkg=" + pkg +
                " node=" + (hit?.label ?: "?") + " at=" + at + " attempts=" + attempts,
            ""
        )
    }

    /**
     * One look at the tree for [needle].
     *
     * [first] is the candidate to press (installer package preferred), [matches]
     * counts every node whose label matched, [inertMatches] the subset that
     * matched but cannot be pressed — the number that turns a useless timeout into
     * "`TEXT` is on screen but it is not a button".
     */
    internal class LabelScan(
        val first: Inspection?,
        val matches: Int,
        val inertMatches: Int
    )

    /** Service-backed wrapper; the decision itself is [scanLabelIn]. */
    private fun scanLabel(needle: String, service: AccessibilityService): LabelScan {
        val roots = (service as? PhoneAccessibilityService)?.roots().orEmpty()
        return scanLabelIn(needle, roots)
    }

    /**
     * The pure half of `confirm`, over framework-free [NodeView]s (SPEC.md
     * section 10: nothing that needs the service is unit tested, everything that
     * decides is).
     */
    internal fun scanLabelIn(needle: String, roots: List<NodeView>): LabelScan {
        val collector = LabelCollector(needle.lowercase())
        for (root in roots) collector.walk(root, ArrayList())
        return collector.result()
    }

    /**
     * Depth-first walk that classifies every label match.
     *
     * Two slots are kept because the preference is not "first in walk order" but
     * "installer before anything else"; whichever slot is filled first inside its
     * own class still wins, so the result is deterministic for a given tree.
     */
    internal class LabelCollector(private val needle: String) {

        var matches = 0
            private set
        var inertMatches = 0
            private set

        private var installerFirst: Inspection? = null
        private var anyFirst: Inspection? = null

        fun walk(node: NodeView, ancestors: MutableList<NodeView>) {
            if (labelMatches(node)) {
                matches++
                val read = inspectionOf(node, ancestors)
                if (isPressable(node, read)) {
                    if (isInstallerPackage(node.packageName)) {
                        if (installerFirst == null) installerFirst = read
                    } else if (anyFirst == null) {
                        anyFirst = read
                    }
                } else {
                    inertMatches++
                }
            }
            ancestors.add(node)
            try {
                val children = node.children
                var i = 0
                while (i < children.size) {
                    walk(children[i], ancestors)
                    i++
                }
            } finally {
                ancestors.removeAt(ancestors.size - 1)
            }
        }

        fun result(): LabelScan = LabelScan(installerFirst ?: anyFirst, matches, inertMatches)

        private fun labelMatches(node: NodeView): Boolean {
            val text = node.text
            if (text != null && text.lowercase().contains(needle)) return true
            val desc = node.desc
            return desc != null && desc.lowercase().contains(needle)
        }

        /**
         * A match is pressable when something can receive the click: the node, a
         * clickable ancestor, or — for a node whose class says "I am a button" but
         * that does not report itself clickable — the node itself via a gesture.
         */
        private fun isPressable(node: NodeView, read: Inspection): Boolean {
            if (read.target != null) return true
            return node.className?.contains("Button", ignoreCase = true) == true
        }

        /** Same resolution [Selector] does: the node, else its nearest clickable ancestor. */
        private fun inspectionOf(node: NodeView, ancestors: List<NodeView>): Inspection {
            var target: NodeView? = if (node.clickable) node else null
            var depth = 0
            if (target == null) {
                var i = ancestors.size - 1
                while (i >= 0) {
                    if (ancestors[i].clickable) {
                        target = ancestors[i]
                        depth = ancestors.size - i
                        break
                    }
                    i--
                }
            }
            val hit = SelectorHit(node, target, depth, Selector.label(node))
            val bounds = (target ?: node).bounds
            return Inspection(hit, target, centreOf(bounds), 1)
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
    fun inspectNow(selector: Selector, service: AccessibilityService): Inspection =
        inspect(selector, rootsOf(service))

    /**
     * The same read, but keeping the roots it was made from. `click` needs them:
     * [ClickPlan.ancestorsOf] looks the matched node up by identity, which only
     * works inside the one tree snapshot that produced it.
     */
    internal fun snapshotNow(selector: Selector, service: AccessibilityService): Snapshot {
        val roots = rootsOf(service)
        return Snapshot(roots, inspect(selector, roots))
    }

    /** One tree read: the roots as they were, plus what the selector found in them. */
    internal class Snapshot(val roots: List<NodeView>, val read: Inspection)

    /** Every window root, or an empty list when the service is not ours. */
    internal fun rootsOf(service: AccessibilityService): List<NodeView> =
        (service as? PhoneAccessibilityService)?.roots().orEmpty()

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

    /** How long `confirm` waits between two looks at the tree. */
    private const val CONFIRM_POLL_MS = 400L

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
