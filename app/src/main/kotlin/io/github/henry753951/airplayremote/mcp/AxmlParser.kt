package io.github.henry753951.airplayremote.mcp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One element of a binary XML file. Attribute names have no namespace prefix. */
class XmlElement(val name: String, val attrs: Map<String, String>, val children: MutableList<XmlElement> = mutableListOf()) {
    fun all(tag: String): List<XmlElement> = children.filter { it.name == tag }
}

/**
 * A reader of the binary XML of `AndroidManifest.xml` in an APK (spec 011 D8). `PackageManager` does not give
 * the intent filters of other apps, so `app_info` reads them here. Pure, with unit tests.
 */
object AxmlParser {
    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103
    private const val RES_XML_RESOURCE_MAP = 0x0180
    private const val UTF8_FLAG = 0x100

    /** Attribute names by resource id: obfuscated APKs keep the ids and drop the names. */
    private val KNOWN = mapOf(
        0x01010003 to "name", 0x01010010 to "exported", 0x01010006 to "permission", 0x01010018 to "authorities",
        0x01010024 to "value", 0x01010025 to "resource", 0x01010026 to "mimeType", 0x01010027 to "scheme",
        0x01010028 to "host", 0x01010029 to "port", 0x0101002a to "path", 0x0101002b to "pathPrefix",
        0x0101002c to "pathPattern", 0x010105c5 to "pathAdvancedPattern", 0x0101021b to "versionCode",
        0x0101021c to "versionName", 0x0101000e to "enabled", 0x01010001 to "label", 0x010103a7 to "targetActivity",
    )

    class FormatError(message: String) : Exception(message)

    fun parse(bytes: ByteArray): XmlElement {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8 || (b.getShort(0).toInt() and 0xFFFF) != RES_XML) throw FormatError("not binary XML")
        var strings: List<String> = emptyList()
        var resIds = IntArray(0)
        val stack = ArrayList<XmlElement>()
        var root: XmlElement? = null
        var pos = b.getShort(2).toInt() and 0xFFFF
        while (pos + 8 <= bytes.size) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val headerSize = b.getShort(pos + 2).toInt() and 0xFFFF
            val size = b.getInt(pos + 4)
            if (size < 8 || pos + size > bytes.size) throw FormatError("bad chunk at $pos")
            when (type) {
                RES_STRING_POOL -> strings = stringPool(b, pos)
                RES_XML_RESOURCE_MAP -> resIds = IntArray((size - headerSize) / 4) { b.getInt(pos + headerSize + it * 4) }
                RES_XML_START_ELEMENT -> {
                    val ext = pos + headerSize
                    val name = strings.getOrElse(b.getInt(ext + 4)) { "?" }
                    val attrStart = b.getShort(ext + 8).toInt() and 0xFFFF
                    val attrSize = b.getShort(ext + 10).toInt() and 0xFFFF
                    val count = b.getShort(ext + 12).toInt() and 0xFFFF
                    val attrs = LinkedHashMap<String, String>()
                    for (i in 0 until count) {
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = b.getInt(a + 4)
                        val key = resIds.getOrNull(nameIdx)?.let { KNOWN[it] }
                            ?: strings.getOrElse(nameIdx) { "" }.ifEmpty { "attr$nameIdx" }
                        attrs[key] = value(b, a, strings)
                    }
                    val el = XmlElement(name, attrs)
                    if (stack.isEmpty()) root = root ?: el else stack.last().children += el
                    stack += el
                }
                RES_XML_END_ELEMENT -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            pos += size
        }
        return root ?: throw FormatError("no root element")
    }

    private fun value(b: ByteBuffer, a: Int, strings: List<String>): String {
        val raw = b.getInt(a + 8)
        val dataType = b.get(a + 15).toInt() and 0xFF
        val data = b.getInt(a + 16)
        return when (dataType) {
            0x03 -> strings.getOrElse(data) { "" }
            0x12 -> (data != 0).toString()
            0x10 -> data.toString()
            0x11 -> "0x" + Integer.toHexString(data)
            0x01 -> if (raw >= 0) strings.getOrElse(raw) { "" } else "@0x" + Integer.toHexString(data)
            else -> if (raw >= 0) strings.getOrElse(raw) { "" } else data.toString()
        }
    }

    private fun stringPool(b: ByteBuffer, pos: Int): List<String> {
        val count = b.getInt(pos + 8)
        val utf8 = b.getInt(pos + 16) and UTF8_FLAG != 0
        val stringsStart = pos + b.getInt(pos + 20)
        val offsets = pos + (b.getShort(pos + 2).toInt() and 0xFFFF)
        return List(count) { i ->
            var p = stringsStart + b.getInt(offsets + i * 4)
            if (utf8) {
                p += if (b.get(p).toInt() and 0x80 != 0) 2 else 1
                var len = b.get(p).toInt() and 0xFF
                if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (b.get(p + 1).toInt() and 0xFF); p += 2 } else p += 1
                val out = ByteArray(len)
                for (j in 0 until len) out[j] = b.get(p + j)
                String(out, Charsets.UTF_8)
            } else {
                var len = b.getShort(p).toInt() and 0xFFFF
                if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (b.getShort(p + 2).toInt() and 0xFFFF); p += 4 } else p += 2
                val chars = CharArray(len) { j -> b.getShort(p + j * 2).toInt().toChar() }
                String(chars)
            }
        }
    }
}

/** What `app_info` takes from the manifest: activity filters, exported providers, search support. */
object ManifestSummary {
    data class Filter(
        val actions: List<String>,
        val categories: List<String>,
        val schemes: List<String> = emptyList(),
        val hosts: List<String> = emptyList(),
        val paths: List<String> = emptyList(),
        val mimeTypes: List<String> = emptyList(),
    )
    data class Component(val kind: String, val name: String, val exported: Boolean, val filters: List<Filter>, val searchable: Boolean, val extra: String? = null)

    fun components(manifest: XmlElement): List<Component> {
        val pkg = manifest.attrs["package"].orEmpty()
        val app = manifest.all("application").firstOrNull() ?: return emptyList()
        return app.children.mapNotNull { c ->
            val kind = when (c.name) { "activity", "activity-alias" -> "activity"; "provider" -> "provider"; "service" -> "service"; "receiver" -> "receiver"; else -> return@mapNotNull null }
            val filters = c.all("intent-filter").map { f ->
                val data = f.all("data").map { it.attrs }
                Filter(
                    actions = f.all("action").mapNotNull { it.attrs["name"] },
                    categories = f.all("category").mapNotNull { it.attrs["name"] },
                    schemes = data.mapNotNull { it["scheme"] }.distinct(),
                    hosts = data.mapNotNull { d -> d["host"]?.let { h -> d["port"]?.let { "$h:$it" } ?: h } }.distinct(),
                    paths = data.flatMap { d ->
                        listOfNotNull(d["path"], d["pathPrefix"]?.let { "$it*" }, d["pathPattern"], d["pathAdvancedPattern"])
                    }.map(::pathText).distinct(),
                    mimeTypes = data.mapNotNull { it["mimeType"] }.distinct(),
                )
            }
            // Without the attribute, a component with a filter is exported (the rule before API 31).
            val exported = c.attrs["exported"]?.toBooleanStrictOrNull() ?: filters.isNotEmpty()
            val searchable = c.all("meta-data").any { it.attrs["name"] == "android.app.searchable" }
            Component(kind, full(pkg, c.attrs["name"].orEmpty()), exported, filters, searchable, c.attrs["authorities"])
        }
    }

    // A pattern for any path (dot star) shows as slash star. A path always starts with a slash.
    private fun pathText(p: String): String = when {
        p == ".*" || p == "/.*" -> "/*"
        p.startsWith("/") -> p
        else -> "/$p"
    }

    private fun full(pkg: String, name: String) = when {
        name.startsWith(".") -> pkg + name
        !name.contains('.') && name.isNotEmpty() -> "$pkg.$name"
        else -> name
    }

    /**
     * Deep links: `scheme://host/path` of all VIEW filters of exported activities. The data elements of one filter
     * combine, so a filter with two schemes and two hosts gives four links.
     */
    fun deepLinks(components: List<Component>): List<String> {
        val out = LinkedHashSet<String>()
        for (c in components) {
            if (c.kind != "activity" || !c.exported) continue
            for (f in c.filters) {
                if ("android.intent.action.VIEW" !in f.actions || f.schemes.isEmpty()) continue
                for (s in f.schemes) {
                    if (s == "content" || s == "file") continue
                    if (f.hosts.isEmpty()) { out += "$s:"; continue }
                    for (h in f.hosts) {
                        if (f.paths.isEmpty()) out += "$s://$h" else for (p in f.paths) out += "$s://$h$p"
                    }
                }
            }
        }
        return out.toList()
    }
}
