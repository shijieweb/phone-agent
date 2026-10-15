package net.de5.phoneagent

/**
 * Element selectors, SPEC.md section 3.1.
 *
 * This file has **no Android imports on purpose**: it works over the
 * framework-free [NodeView] so the whole matching contract — parsing, substring
 * matching, case folding, walk order, clickable-ancestor resolution — is pure
 * logic that runs on a plain JVM (SPEC.md section 10).
 */

/** Result of walking a tree: the hit, its action target and how it was reached. */
data class SelectorHit(
    /** The node the selector matched. */
    val node: NodeView,
    /**
     * Where the action should land: the hit itself when clickable, otherwise the
     * nearest clickable ancestor. `null` when neither exists.
     */
    val clickableTarget: NodeView?,
    /** 0 when the hit is directly clickable, otherwise how many levels up. */
    val ancestorDepth: Int,
    /** Reporting label: `resId`, else the first 40 chars of `text`, else `desc`. */
    val label: String
)

/**
 * A parsed selector. `SEL := [kind:]value`, where `value` is the rest of the line
 * **verbatim** — spaces included, exactly like `text` (SPEC.md section 3).
 *
 * [raw] keeps the operator's original text so error messages can quote it back
 * exactly as typed (`no node matched: <SEL>`), rather than a normalised form.
 */
data class Selector(val kind: Kind, val value: String, val raw: String = value) {

    enum class Kind { ANY, TEXT, DESC, ID, CLS }

    /** `true` when [node] carries *some* field this selector is allowed to look at. */
    fun matches(node: NodeView): Boolean {
        if (value.isEmpty()) return false
        return when (kind) {
            Kind.TEXT -> contains(node.text)
            Kind.DESC -> contains(node.desc)
            Kind.ID -> contains(node.resId)
            Kind.CLS -> contains(node.className)
            Kind.ANY ->
                contains(node.text) || contains(node.desc) || contains(node.resId)
        }
    }

    private fun contains(field: String?): Boolean {
        if (field == null) return false
        return field.lowercase().contains(value.lowercase())
    }

    /**
     * The **first** match, depth-first pre-order, across all windows — the same
     * enumeration as `tree` (SPEC.md section 3.1).
     */
    fun find(roots: List<NodeView>): SelectorHit? {
        for (root in roots) {
            val hit = walk(root, mutableListOf())
            if (hit != null) return hit
        }
        return null
    }

    /** Every match, so the operator can see how ambiguous a selector was. */
    fun findAll(roots: List<NodeView>): List<SelectorHit> {
        val out = ArrayList<SelectorHit>()
        for (root in roots) collect(root, mutableListOf(), out)
        return out
    }

    /**
     * @param ancestors the path from the window root to [node]'s parent, nearest
     *                  last, used to resolve the clickable ancestor.
     */
    private fun walk(node: NodeView, ancestors: MutableList<NodeView>): SelectorHit? {
        if (matches(node)) return hit(node, ancestors)
        ancestors.add(node)
        try {
            val children = node.children
            var i = 0
            while (i < children.size) {
                val found = walk(children[i], ancestors)
                if (found != null) return found
                i++
            }
        } finally {
            ancestors.removeAt(ancestors.size - 1)
        }
        return null
    }

    private fun collect(node: NodeView, ancestors: MutableList<NodeView>, out: MutableList<SelectorHit>) {
        if (matches(node)) out.add(hit(node, ancestors))
        ancestors.add(node)
        try {
            val children = node.children
            var i = 0
            while (i < children.size) {
                collect(children[i], ancestors, out)
                i++
            }
        } finally {
            ancestors.removeAt(ancestors.size - 1)
        }
    }

    /** Nearest clickable ancestor: walk the recorded path from the end backwards. */
    private fun hit(node: NodeView, ancestors: MutableList<NodeView>): SelectorHit {
        if (node.clickable) return SelectorHit(node, node, 0, label(node))
        var i = ancestors.size - 1
        while (i >= 0) {
            val candidate = ancestors[i]
            if (candidate.clickable) {
                return SelectorHit(node, candidate, ancestors.size - i, label(node))
            }
            i--
        }
        return SelectorHit(node, null, 0, label(node))
    }

    companion object {

        /** Longest label we print for a node, per SPEC.md section 3.1. */
        const val LABEL_MAX_CHARS = 40

        private val KINDS = mapOf(
            "text" to Kind.TEXT,
            "desc" to Kind.DESC,
            "id" to Kind.ID,
            "cls" to Kind.CLS
        )

        /**
         * Parse the rest of a command line. Never throws: anything without a
         * recognised `kind:` prefix is treated as a bare value matched against
         * text/description/resource id.
         *
         * Note the deliberate asymmetry with `text`: parsing trims the selector
         * only at the ends. Inner spaces are significant (`text:delete all`),
         * because a selector is meant to be pasted, not assembled byte by byte.
         */
        fun parse(raw: String): Selector {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return Selector(Kind.ANY, "", trimmed)

            val colon = trimmed.indexOf(':')
            if (colon > 0) {
                val head = trimmed.substring(0, colon).lowercase()
                val kind = KINDS[head]
                if (kind != null) {
                    return Selector(kind, trimmed.substring(colon + 1), trimmed)
                }
            }
            return Selector(Kind.ANY, trimmed, trimmed)
        }

        /**
         * Reporting label for a node: `resId` when present, else the first
         * [LABEL_MAX_CHARS] of `text`, else `desc` (SPEC.md section 3.1).
         */
        fun label(node: NodeView): String {
            val resId = node.resId
            if (!resId.isNullOrEmpty()) return resId
            val text = node.text
            if (!text.isNullOrEmpty()) {
                return if (text.length > LABEL_MAX_CHARS) text.substring(0, LABEL_MAX_CHARS) else text
            }
            val desc = node.desc
            if (!desc.isNullOrEmpty()) {
                return if (desc.length > LABEL_MAX_CHARS) desc.substring(0, LABEL_MAX_CHARS) else desc
            }
            return node.className ?: ""
        }
    }
}

/**
 * Finds the first node that is `scrollable` in a depth-first pre-order walk.
 *
 * `scroll` with an optional selector behaves differently from `click`: the
 * selector, when present, *narrows* the search to a subtree, but the node that
 * actually performs the action is the nearest scrollable descendant-or-self
 * (SPEC.md section 3.1: "if no scrollable node is found → exit=5").
 */
object ScrollTarget {

    /** @return the nearest scrollable node at or below [node], or null. */
    fun at(node: NodeView): NodeView? {
        if (node.scrollable) return node
        val children = node.children
        var i = 0
        while (i < children.size) {
            val hit = at(children[i])
            if (hit != null) return hit
            i++
        }
        return null
    }

    /**
     * First scrollable node in the whole tree, walking in the documented order.
     * Used by `scroll` with no selector.
     */
    fun first(roots: List<NodeView>): NodeView? {
        for (root in roots) {
            val hit = at(root)
            if (hit != null) return hit
        }
        return null
    }
}
