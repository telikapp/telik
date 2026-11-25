package io.github.henry753951.airplayremote.mcp

/** One node of the screen, in plain data (spec 011 D5). */
data class UiNode(
    val cls: String,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val visible: Boolean = true,
    val focused: Boolean = false,
    val clickable: Boolean = false,
    val focusable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val hint: String? = null,
)

/** The compact form of the UI tree: the filter, one line for each node, and the search. Pure, with unit tests. */
object UiTree {
    const val MAX_TEXT = 80
    private const val TINY = 10
    /** Word joiner, zero-width space and joiners, BOM, soft hyphen: KinoPub puts U+2060 into titles. */
    private val INVISIBLE = Regex("[\u2060\u200B\u200C\u200D\uFEFF\u00AD]")

    fun clean(s: String): String = INVISIBLE.replace(s, "")

    /** Keeps visible nodes with text or a description, and nodes that the user can act on. */
    fun keep(n: UiNode): Boolean {
        if (!n.visible || n.right <= n.left || n.bottom <= n.top) return false
        // YouTube (Cobalt) puts a grid of 10x10 px placeholders for the focus of screen readers.
        if (n.right - n.left <= TINY && n.bottom - n.top <= TINY && n.text.isNullOrBlank() && n.desc.isNullOrBlank()) return false
        return !n.text.isNullOrBlank() || !n.desc.isNullOrBlank() || n.clickable || n.focusable || n.scrollable ||
            n.editable || n.checkable || n.focused
    }

    const val NO_TREE = "(no text in the tree: the app draws its own UI. Use recipe, app_info deep links, open, media and key)"

    fun role(cls: String): String = cls.substringAfterLast('.').ifEmpty { "View" }

    /** `com.app:id/search_button` becomes `search_button` for the app itself; other packages keep the prefix. */
    fun shortId(viewId: String?, pkg: String?): String? {
        if (viewId.isNullOrBlank()) return null
        if (pkg != null && viewId.startsWith("$pkg:id/")) return viewId.removePrefix("$pkg:id/")
        if (viewId.startsWith("android:id/")) return viewId.removePrefix("android:")
        return viewId
    }

    private fun clip(s: String): String {
        val one = clean(s).replace('\n', ' ').replace('"', '\'').trim()
        return if (one.length > MAX_TEXT) one.take(MAX_TEXT - 1) + "…" else one
    }

    /** `#12 Button "Search" desc="…" id=search [100,200,300,260] focused clickable` */
    fun line(index: Int, n: UiNode, pkg: String?): String {
        val sb = StringBuilder("#").append(index).append(' ').append(role(n.cls))
        n.text?.takeIf { it.isNotBlank() }?.let { sb.append(" \"").append(clip(it)).append('"') }
        n.desc?.takeIf { it.isNotBlank() && it != n.text }?.let { sb.append(" desc=\"").append(clip(it)).append('"') }
        n.hint?.takeIf { it.isNotBlank() && it != n.text }?.let { sb.append(" hint=\"").append(clip(it)).append('"') }
        shortId(n.viewId, pkg)?.let { sb.append(" id=").append(it) }
        sb.append(" [").append(n.left).append(',').append(n.top).append(',').append(n.right).append(',').append(n.bottom).append(']')
        if (n.focused) sb.append(" focused")
        if (n.selected) sb.append(" selected")
        if (n.clickable) sb.append(" clickable")
        if (n.scrollable) sb.append(" scrollable")
        if (n.editable) sb.append(" editable")
        if (n.checkable) sb.append(if (n.checked) " checked" else " unchecked")
        if (!n.enabled) sb.append(" disabled")
        return sb.toString()
    }

    /** Case-insensitive part match on the text, the description, the hint or the view id. */
    fun matches(n: UiNode, query: String): Boolean {
        val q = clean(query).trim().lowercase()
        if (q.isEmpty()) return false
        return listOfNotNull(n.text, n.desc, n.hint, n.viewId).any { clean(it).lowercase().contains(q) }
    }

    /** The text of the whole answer, with a note when the list was cut at [max]. */
    fun render(header: String, nodes: List<UiNode>, pkg: String?, max: Int, indexes: List<Int> = nodes.indices.toList()): String {
        val sb = StringBuilder(header)
        val shown = indexes.take(max)
        for (i in shown) sb.append('\n').append(line(i, nodes[i], pkg))
        if (indexes.size > shown.size) sb.append("\n… ").append(indexes.size - shown.size).append(" more nodes; use find or a larger max")
        if (indexes.none { !nodes[it].text.isNullOrBlank() || !nodes[it].desc.isNullOrBlank() }) sb.append("\n$NO_TREE")
        return sb.toString()
    }
}
