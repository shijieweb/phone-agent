package net.de5.phoneagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Screen rectangle of a node: left, top, right, bottom. */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * A read-only, framework-free view of one accessibility node.
 *
 * The tree formatter ([UiTree.format]) only ever sees [NodeView], never
 * [AccessibilityNodeInfo], which is what keeps it unit-testable on a plain JVM
 * (SPEC.md section 10). The only implementation that touches the framework is
 * [AccessibilityNodeAdapter] at the bottom of this file.
 */
interface NodeView {
    val className: String?
    val text: String?
    val desc: String?
    val resId: String?
    val bounds: NodeBounds?
    val clickable: Boolean
    val longClickable: Boolean
    val focusable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val checked: Boolean
    val enabled: Boolean
    val selected: Boolean
    val visibleToUser: Boolean
    val children: List<NodeView>
}

/** Serialises a node tree to the compact line format of SPEC.md section 5. */
object UiTree {

    /** text / desc / resId are truncated to this many characters. */
    const val FIELD_MAX_CHARS = 60

    const val DEFAULT_MAX_NODES = 400
    const val HARD_MAX_NODES = 800

    /**
     * One line per emitted node:
     * `<depth>|<class>|<text>|<desc>|<resId>|<bounds>|<flags>`.
     *
     * Nodes with no text, no description, no resource id and no flags are not
     * emitted, but their children are still walked. Once [maxNodes] lines have
     * been written the walk stops and a `... truncated at <n> nodes` line is
     * appended.
     */
    fun format(roots: List<NodeView>, maxNodes: Int): String {
        val limit = maxNodes.coerceIn(1, HARD_MAX_NODES)
        val lines = ArrayList<String>()
        val emitted = intArrayOf(0)
        var truncated = false

        for (root in roots) {
            if (emitted[0] >= limit) {
                truncated = true
                break
            }
            if (walk(root, 0, limit, lines, emitted)) {
                truncated = true
                break
            }
        }

        if (truncated) lines.add("... truncated at ${emitted[0]} nodes")
        return lines.joinToString("\n")
    }

    /** @return true when the walk had to stop because the limit was reached. */
    private fun walk(
        node: NodeView,
        depth: Int,
        limit: Int,
        out: MutableList<String>,
        emitted: IntArray
    ): Boolean {
        val line = render(node, depth)
        if (line != null) {
            if (emitted[0] >= limit) return true
            out.add(line)
            emitted[0]++
        }
        val children = node.children
        var i = 0
        while (i < children.size) {
            if (walk(children[i], depth + 1, limit, out, emitted)) return true
            i++
        }
        return false
    }

    /** @return the line for this node, or null when it carries no information. */
    private fun render(node: NodeView, depth: Int): String? {
        val text = field(node.text)
        val desc = field(node.desc)
        val resId = field(node.resId)
        val flagText = flags(node)
        if (text.isEmpty() && desc.isEmpty() && resId.isEmpty() && flagText.isEmpty()) return null

        val className = sanitize(node.className)
        val bounds = node.bounds
        val boundsText = if (bounds == null) {
            ""
        } else {
            "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        }
        return "$depth|$className|$text|$desc|$resId|$boundsText|$flagText"
    }

    /**
     * Flag letters, only the ones that are true:
     * `c`=clickable `l`=long-clickable `f`=focusable `e`=editable `s`=scrollable
     * `x`=checked `d`=disabled `k`=selected.
     */
    private fun flags(node: NodeView): String {
        val sb = StringBuilder(9)
        if (node.clickable) sb.append('c')
        if (node.longClickable) sb.append('l')
        if (node.focusable) sb.append('f')
        if (node.editable) sb.append('e')
        if (node.scrollable) sb.append('s')
        if (node.checked) sb.append('x')
        if (!node.enabled) sb.append('d')
        if (node.selected) sb.append('k')
        return sb.toString()
    }

    private fun field(value: String?): String {
        val clean = sanitize(value)
        return if (clean.length > FIELD_MAX_CHARS) clean.substring(0, FIELD_MAX_CHARS) else clean
    }

    private fun sanitize(value: String?): String {
        if (value == null) return ""
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            sb.append(if (c == '\n' || c == '\r' || c == '|') '/' else c)
            i++
        }
        return sb.toString()
    }
}

/**
 * The only place that reads [AccessibilityNodeInfo]. Kept apart from [UiTree]
 * so the formatter stays pure.
 */
open class AccessibilityNodeAdapter(private val node: AccessibilityNodeInfo) : NodeView {

    override val className: String?
        get() = node.className?.toString()

    override val text: String?
        get() = node.text?.toString()

    override val desc: String?
        get() = node.contentDescription?.toString()

    override val resId: String?
        get() = node.viewIdResourceName

    override val bounds: NodeBounds?
        get() {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val clickable: Boolean
        get() = node.isClickable

    override val longClickable: Boolean
        get() = node.isLongClickable

    override val focusable: Boolean
        get() = node.isFocusable

    override val editable: Boolean
        get() = node.isEditable

    override val scrollable: Boolean
        get() = node.isScrollable

    override val checked: Boolean
        get() = node.isChecked

    override val enabled: Boolean
        get() = node.isEnabled

    override val selected: Boolean
        get() = node.isSelected

    override val visibleToUser: Boolean
        get() = node.isVisibleToUser

    override val children: List<NodeView>
        get() {
            val count = node.childCount
            if (count <= 0) return emptyList()
            val list = ArrayList<NodeView>(count)
            var i = 0
            while (i < count) {
                val child = node.getChild(i)
                if (child != null) list.add(AccessibilityNodeAdapter(child))
                i++
            }
            return list
        }

    /** The framework node behind this view; used by [ElementActions] only. */
    fun frameworkNode(): AccessibilityNodeInfo = node
}
