package io.github.henry753951.airplayremote.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A small writer of binary XML, so the parser has a test without an APK. */
private class AxmlWriter(private val utf8: Boolean) {
    private val strings = ArrayList<String>()
    private val attrIds = ArrayList<Int>()
    private val body = ByteArrayOutputStream()

    /** Attribute names first: their index must match the resource map. */
    fun attrName(name: String, id: Int): Int { strings += name; attrIds += id; return strings.size - 1 }
    fun str(s: String): Int = strings.indexOf(s).takeIf { it >= 0 } ?: run { strings += s; strings.size - 1 }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun start(name: String, attrs: List<Triple<Int, Int, Int>>) {
        // attrs: name index, data type, data
        val n = str(name)
        val b = le(36 + attrs.size * 20)
        b.putShort(0x0102).putShort(16).putInt(36 + attrs.size * 20).putInt(1).putInt(-1)
        b.putInt(-1).putInt(n).putShort(20).putShort(20).putShort(attrs.size.toShort()).putShort(0).putShort(0).putShort(0)
        for ((an, type, data) in attrs) {
            b.putInt(-1).putInt(an).putInt(if (type == 3) data else -1).putShort(8).put(0).put(type.toByte()).putInt(data)
        }
        body.write(b.array())
    }

    fun end(name: String) {
        val b = le(24)
        b.putShort(0x0103).putShort(16).putInt(24).putInt(1).putInt(-1).putInt(-1).putInt(str(name))
        body.write(b.array())
    }

    fun bytes(): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (s in strings) {
            offsets += data.size()
            if (utf8) {
                val u = s.toByteArray(Charsets.UTF_8)
                data.write(s.length); data.write(u.size); data.write(u); data.write(0)
            } else {
                val l = le(2 + s.length * 2 + 2)
                l.putShort(s.length.toShort()); s.forEach { l.putChar(it) }; l.putShort(0)
                data.write(l.array())
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val poolHeader = 28
        val poolSize = poolHeader + strings.size * 4 + data.size()
        val pool = le(poolHeader + strings.size * 4)
        pool.putShort(0x0001).putShort(poolHeader.toShort()).putInt(poolSize).putInt(strings.size).putInt(0)
            .putInt(if (utf8) 0x100 else 0).putInt(poolHeader + strings.size * 4).putInt(0)
        offsets.forEach { pool.putInt(it) }
        val map = le(8 + attrIds.size * 4)
        map.putShort(0x0180).putShort(8).putInt(8 + attrIds.size * 4)
        attrIds.forEach { map.putInt(it) }
        val rest = ByteArrayOutputStream().apply { write(pool.array()); write(data.toByteArray()); write(map.array()); write(body.toByteArray()) }
        val head = le(8).putShort(0x0003).putShort(8).putInt(8 + rest.size())
        return head.array() + rest.toByteArray()
    }
}

class AxmlParserTest {
    private fun manifest(utf8: Boolean): ByteArray {
        val w = AxmlWriter(utf8)
        // Obfuscated names: empty strings, only the resource ids say what they are.
        val name = w.attrName("", 0x01010003)
        val exported = w.attrName("exported", 0x01010010)
        val scheme = w.attrName("", 0x01010027)
        val host = w.attrName("host", 0x01010028)
        val prefix = w.attrName("pathPrefix", 0x0101002b)
        val authorities = w.attrName("authorities", 0x01010018)
        val pattern = w.attrName("pathPattern", 0x0101002c)
        val pkg = w.str("package")
        val s = { v: String -> Triple(0, 3, w.str(v)) }
        fun a(i: Int, v: String) = Triple(i, 3, w.str(v))
        w.start("manifest", listOf(Triple(pkg, 3, w.str("com.example.tv"))))
        w.start("application", emptyList())
        w.start("activity", listOf(a(name, ".Main"), Triple(exported, 0x12, -1)))
        w.start("intent-filter", emptyList())
        w.start("action", listOf(a(name, "android.intent.action.VIEW"))); w.end("action")
        w.start("category", listOf(a(name, "android.intent.category.BROWSABLE"))); w.end("category")
        w.start("data", listOf(a(scheme, "https"))); w.end("data")
        w.start("data", listOf(a(scheme, "http"))); w.end("data")
        w.start("data", listOf(a(host, "tv.example.com"), a(prefix, "/watch"))); w.end("data")
        w.start("data", listOf(a(pattern, ".*"))); w.end("data")
        w.end("intent-filter")
        w.start("intent-filter", emptyList())
        w.start("action", listOf(a(name, "android.intent.action.SEARCH"))); w.end("action")
        w.end("intent-filter")
        w.start("meta-data", listOf(a(name, "android.app.searchable"))); w.end("meta-data")
        w.end("activity")
        w.start("activity", listOf(a(name, "com.example.tv.Hidden"), Triple(exported, 0x12, 0)))
        w.start("intent-filter", emptyList())
        w.start("action", listOf(a(name, "android.intent.action.VIEW"))); w.end("action")
        w.start("data", listOf(a(scheme, "secret"))); w.end("data")
        w.end("intent-filter")
        w.end("activity")
        w.start("provider", listOf(a(name, ".Search"), a(authorities, "com.example.search"), Triple(exported, 0x12, -1))); w.end("provider")
        w.end("application")
        w.end("manifest")
        s("unused")
        return w.bytes()
    }

    @Test fun parsesUtf16AndUtf8Pools() {
        for (utf8 in listOf(false, true)) {
            val root = AxmlParser.parse(manifest(utf8))
            assertEquals("manifest", root.name)
            assertEquals("com.example.tv", root.attrs["package"])
            val main = root.all("application")[0].all("activity")[0]
            assertEquals(".Main", main.attrs["name"])
            assertEquals("true", main.attrs["exported"])
        }
    }

    @Test fun picksDeepLinksOfExportedActivities() {
        val comps = ManifestSummary.components(AxmlParser.parse(manifest(false)))
        val main = comps.first { it.name == "com.example.tv.Main" }
        assertTrue(main.searchable)
        assertEquals(listOf("https", "http"), main.filters[0].schemes)
        assertEquals(
            listOf("https://tv.example.com/watch*", "https://tv.example.com/*", "http://tv.example.com/watch*", "http://tv.example.com/*"),
            ManifestSummary.deepLinks(comps),
        )
        assertFalse(comps.first { it.name == "com.example.tv.Hidden" }.exported)
        val provider = comps.first { it.kind == "provider" }
        assertEquals("com.example.search", provider.extra)
    }

    @Test(expected = AxmlParser.FormatError::class) fun refusesTextXml() {
        AxmlParser.parse("<manifest/>".toByteArray())
    }
}
