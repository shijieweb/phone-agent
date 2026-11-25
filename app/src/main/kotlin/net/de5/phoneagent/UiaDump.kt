package net.de5.phoneagent

/**
 * `tree uia` — 读一份 uiautomator dump，转成和 `tree` 完全相同的行格式（v0.6.1-pad12）。
 *
 * 存在的理由：无障碍通道在 Flyme / Android 16 上读到的是**上一次窗口激活时**的
 * 快照，`rootInActiveWindow` 和 `refresh()` 都救不回来（v0.6.0-pad12 真机实测：
 * 展开高级设置后 tree 恒慢一拍，等 8 秒仍是旧值，只有切走再切回才刷新）。同一时刻
 * `uiautomator dump` 读到的是正确的视图树，所以这里走 uid 2000 的 uiautomator
 * 取一份新鲜的 XML，作为 `tree` 的保底通道。
 *
 * 这个对象不 import android.*：解析是纯字符串扫描，行格式直接交给 [UiTree.format]，
 * 所以两条通道的输出逐字节同构（同样的 depth、同样的 flags 字母表、同样的 60 字符
 * 字段上限、同样的截断行），操作员与脚本不用为两条路写两套正则。
 */
internal object UiaDump {

    /** dump 落盘路径固定，读完不删：真机上要靠它手动取证。 */
    const val DUMP_PATH = "/data/local/tmp/phoneagent-tree.xml"

    private const val OK_MARK = "__UIA_OK__"
    private const val FAIL_MARK = "__UIA_FAIL__"

    private const val OPEN_HIERARCHY = "<hierarchy"
    private const val CLOSE_HIERARCHY = "</hierarchy>"

    /**
     * 一条 shell 命令里先 dump 再 cat 回来。
     *
     * 为什么合成一条：一次 PTY 往返约 1 秒，dump 本身 1-2 秒，两次往返就把预算吃掉
     * 一半；而且分成两条时「dump 成功但 cat 失败」要多一个状态。标记必须独占一行
     * —— PTY 会把命令行回显出来，用 contains 判断会被回显里的同名文字骗到，所以
     * 判定是逐行全等。
     */
    fun dumpCommand(): String =
        "if uiautomator dump " + DUMP_PATH + " >/dev/null 2>&1; then echo " + OK_MARK +
            "; else echo " + FAIL_MARK + "; fi; cat " + DUMP_PATH + " 2>/dev/null"

    fun succeeded(shellText: String): Boolean = hasOwnLine(shellText, OK_MARK)

    fun failed(shellText: String): Boolean = hasOwnLine(shellText, FAIL_MARK)

    private fun hasOwnLine(text: String, marker: String): Boolean {
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start)
            val line = if (end < 0) text.substring(start) else text.substring(start, end)
            if (line.trim() == marker) return true
            if (end < 0) break
            start = end + 1
        }
        return false
    }

    /** 只取 `<hierarchy …>…</hierarchy>` 这一段，前后 PTY 回显与提示符全部丢掉。 */
    fun hierarchyOf(shellText: String): String {
        val start = shellText.indexOf(OPEN_HIERARCHY)
        if (start < 0) return ""
        val end = shellText.indexOf(CLOSE_HIERARCHY, start)
        if (end < 0) return shellText.substring(start)
        return shellText.substring(start, end + CLOSE_HIERARCHY.length)
    }

    /**
     * 把 dump 文本转成 [UiTree] 的行格式。
     *
     * @return 行格式的树；文本里没有 `<hierarchy>`、或者根本没有 node 时返回空串
     *   （空串是「没读到」，不是异常 —— 调用方要能把这两种情况分开）。
     */
    fun parse(shellText: String, maxNodes: Int): String {
        val xml = hierarchyOf(shellText)
        if (xml.isEmpty()) return ""
        val root = scan(xml) ?: return ""
        return UiTree.format(listOf(root), maxNodes)
    }

    // ------------------------------------------------------------- 扫描

    /**
     * 扫出树形结构。
     *
     * uiautomator 的 XML 是嵌套的（`<node><node/></node>`），**没有 depth 属性**：
     * 深度就是「外面套了几层 `<node>`」，所以这里维护一个开标签栈，每个新 node
     * 挂到栈里最近的那个 node 上，再由 [UiTree.format] 从根按 0 递增 —— 这正是
     * 无障碍通道的 depth 语义（每个窗口的根都是 0），两条通道才对得上。
     */
    private fun scan(xml: String): UiaNode? {
        val stack = ArrayList<Open>()
        var root: UiaNode? = null
        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            if (xml.startsWith("<!--", lt)) {
                val end = xml.indexOf("-->", lt)
                i = if (end < 0) xml.length else end + 3
                continue
            }
            if (xml.startsWith("<?", lt) || xml.startsWith("<!", lt)) {
                val end = xml.indexOf('>', lt)
                i = if (end < 0) xml.length else end + 1
                continue
            }
            if (xml.startsWith("</", lt)) {
                val end = xml.indexOf('>', lt)
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                i = if (end < 0) xml.length else end + 1
                continue
            }
            val end = tagEnd(xml, lt)
            if (end < 0) break
            val inner = xml.substring(lt + 1, end)
            val selfClosing = inner.endsWith("/")
            val body = if (selfClosing) inner.substring(0, inner.length - 1) else inner
            val name = tagName(body)
            val node = if (name == "node") UiaNode(attributes(body)) else null
            if (node != null) {
                val parent = nearestNode(stack)
                if (parent != null) {
                    parent.kids.add(node)
                } else if (root == null) {
                    root = node
                }
            }
            if (!selfClosing) stack.add(Open(name, node))
            i = end + 1
        }
        return root
    }

    private class Open(val name: String, val node: UiaNode?)

    private fun nearestNode(stack: List<Open>): UiaNode? {
        var i = stack.size - 1
        while (i >= 0) {
            val node = stack[i].node
            if (node != null) return node
            i--
        }
        return null
    }

    /** 找到开标签真正的结束 `>`：属性值里的 `>` 不算。 */
    private fun tagEnd(xml: String, from: Int): Int {
        var i = from + 1
        var quote = 0.toChar()
        while (i < xml.length) {
            val c = xml[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return -1
    }

    private fun tagName(body: String): String {
        var i = 0
        while (i < body.length && body[i].isWhitespace()) i++
        val start = i
        while (i < body.length && !body[i].isWhitespace() && body[i] != '/') i++
        return body.substring(start, i)
    }

    /** `name="value"` 全部取出来；单双引号都认，值里的实体还原。 */
    private fun attributes(body: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < body.length) {
            val eq = body.indexOf('=', i)
            if (eq < 0) break
            var nameEnd = eq
            while (nameEnd > i && body[nameEnd - 1].isWhitespace()) nameEnd--
            var nameStart = nameEnd
            while (nameStart > i && !body[nameStart - 1].isWhitespace() && body[nameStart - 1] != '/') nameStart--
            val name = body.substring(nameStart, nameEnd)
            var j = eq + 1
            while (j < body.length && body[j].isWhitespace()) j++
            if (j >= body.length) break
            val quote = body[j]
            if (quote != '"' && quote != '\'') {
                i = eq + 1
                continue
            }
            val close = body.indexOf(quote, j + 1)
            if (close < 0) break
            if (name.isNotEmpty()) out[name] = unescape(body.substring(j + 1, close))
            i = close + 1
        }
        return out
    }

    private fun unescape(value: String): String {
        if (value.indexOf('&') < 0) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun boundsOf(text: String?): NodeBounds? {
        if (text == null) return null
        val digits = StringBuilder()
        val parts = ArrayList<Int>(4)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isDigit() || c == '-') {
                digits.append(c)
            } else if (digits.isNotEmpty()) {
                parts.add(digits.toString().toIntOrNull() ?: return null)
                digits.setLength(0)
            }
            i++
        }
        if (digits.isNotEmpty()) parts.add(digits.toString().toIntOrNull() ?: return null)
        if (parts.size != 4) return null
        return NodeBounds(parts[0], parts[1], parts[2], parts[3])
    }

    /**
     * 一个 dump 节点，字段名与 uiautomator 的属性名对齐。
     *
     * 唯一一处不是直读属性的是 editable：uiautomator 不输出 editable，但无障碍
     * 通道的 `e` 对 EditText 恒为真，所以这里用「class 以 EditText 结尾」补上，
     * 否则同一个输入框在两条通道里 flags 不同，脚本没法只写一套判断。
     */
    private class UiaNode(private val attrs: Map<String, String>) : NodeView {

        val kids = ArrayList<NodeView>()

        private fun attr(name: String): String? = attrs[name]?.takeIf { it.isNotEmpty() }

        private fun yes(name: String): Boolean = attrs[name] == "true"

        override val className: String? get() = attr("class")

        override val packageName: String? get() = attr("package")

        override val text: String? get() = attr("text")

        override val desc: String? get() = attr("content-desc")

        override val resId: String? get() = attr("resource-id")

        override val bounds: NodeBounds? get() = boundsOf(attr("bounds"))

        override val clickable: Boolean get() = yes("clickable")

        override val longClickable: Boolean get() = yes("long-clickable")

        override val focusable: Boolean get() = yes("focusable")

        override val editable: Boolean get() = yes("editable") || className?.endsWith("EditText") == true

        override val scrollable: Boolean get() = yes("scrollable")

        override val checked: Boolean get() = yes("checked")

        override val enabled: Boolean get() = attrs["enabled"] != "false"

        override val selected: Boolean get() = yes("selected")

        override val visibleToUser: Boolean get() = true

        override val children: List<NodeView> get() = kids
    }
}
