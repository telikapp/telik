package io.github.henry753951.airplayremote.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeTest {
    private fun node(text: String? = null, cls: String = "android.widget.TextView", visible: Boolean = true, clickable: Boolean = false, viewId: String? = null, desc: String? = null) =
        UiNode(cls, text, desc, viewId, 10, 20, 110, 60, visible = visible, clickable = clickable)

    @Test fun keepsVisibleNodesWithTextOrActions() {
        assertTrue(UiTree.keep(node("Search")))
        assertTrue(UiTree.keep(node(clickable = true)))
        assertFalse(UiTree.keep(node()))
        assertFalse(UiTree.keep(node("Hidden", visible = false)))
        assertFalse(UiTree.keep(node("Empty").copy(right = 10)))
    }

    @Test fun dropsTheCobaltPlaceholders() {
        // YouTube on the test TV: a 3x3 grid of empty 10x10 px views.
        assertFalse(UiTree.keep(UiNode("android.view.View", null, null, null, 10, 0, 20, 10, focusable = true)))
        assertTrue(UiTree.keep(UiNode("android.view.View", "OK", null, null, 10, 0, 20, 10)))
    }

    @Test fun ignoresInvisibleCharacters() {
        // KinoPub on the test TV: "Дюн\u2060а".
        assertTrue(UiTree.matches(node("Дюн\u2060а"), "Дюна"))
        assertEquals("#0 TextView \"Дюна\" [10,20,110,60]", UiTree.line(0, node("Дюн\u2060а"), null))
    }

    @Test fun formatsOneLine() {
        val n = UiNode("android.widget.Button", "Search", "Search videos", "com.app:id/search", 100, 200, 300, 260, focused = true, clickable = true)
        assertEquals("#4 Button \"Search\" desc=\"Search videos\" id=search [100,200,300,260] focused clickable", UiTree.line(4, n, "com.app"))
        assertEquals("other:id/x", UiTree.shortId("other:id/x", "com.app"))
        assertEquals("id/content", UiTree.shortId("android:id/content", "com.app"))
    }

    @Test fun clipsLongText() {
        val line = UiTree.line(0, node("a".repeat(200) + "\n\"q\""), null)
        assertTrue(line.length < 120)
        assertTrue(line.contains("…"))
    }

    @Test fun findsByTextDescriptionOrId() {
        assertTrue(UiTree.matches(node("Главная"), "главн"))
        assertTrue(UiTree.matches(node(desc = "Search"), "SEARCH"))
        assertTrue(UiTree.matches(node(viewId = "com.app:id/play_button"), "play_button"))
        assertFalse(UiTree.matches(node("Home"), " "))
    }

    @Test fun rendersALimitAndAnEmptyNote() {
        val nodes = List(5) { node("n$it") }
        val text = UiTree.render("app=x v=1", nodes, null, 3)
        assertEquals(5, text.lines().size)
        assertTrue(text.endsWith("2 more nodes; use find or a larger max"))
        assertTrue(UiTree.render("h", emptyList(), null, 10).contains(UiTree.NO_TREE))
        assertTrue(UiTree.render("h", listOf(node(clickable = true)), null, 10).contains(UiTree.NO_TREE))
        assertFalse(UiTree.render("h", nodes, null, 10).contains(UiTree.NO_TREE))
        assertTrue(UiTree.render("h", nodes, null, 10, listOf(3)).contains("#3 TextView \"n3\""))
    }
}
