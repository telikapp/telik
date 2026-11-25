package io.github.henry753951.airplayremote.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    private val token = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-23"
    private val tools = listOf(
        McpTool("echo", "Echo text.", listOf("text" to "string:what to echo", "times?" to "integer")) { a -> ToolResult(a.str("text")) },
        McpTool("fail", "Fails.", action = true) { _ -> throw ToolError("no node #3") },
        McpTool("pick", "Pick.", listOf("kind?" to "enum(a|b):kind")) { _ -> ToolResult("", "image/jpeg" to "AAAA") },
    )
    private val p = McpProtocol(tools, "3.2.0")

    private fun rpc(method: String, params: String = "{}", id: Int? = 1) =
        p.handle("""{"jsonrpc":"2.0",${id?.let { "\"id\":$it," } ?: ""}"method":"$method","params":$params}""")?.let(::JSONObject)

    private fun request(method: String = "POST", path: String = "/mcp", auth: String? = "Bearer $token", origin: String? = null, body: String = "") =
        McpHttp.Request(method, path, buildMap {
            auth?.let { put("authorization", it) }
            origin?.let { put("origin", it) }
        }, body.toByteArray())

    @Test fun initializeAgreesOnTheVersion() {
        val r = rpc("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")!!.getJSONObject("result")
        assertEquals("2025-06-18", r.getString("protocolVersion"))
        assertEquals("telik", r.getJSONObject("serverInfo").getString("name"))
        assertTrue(r.getJSONObject("capabilities").has("tools"))
        val newest = rpc("initialize", """{"protocolVersion":"2099-01-01"}""")!!.getJSONObject("result")
        assertEquals(McpProtocol.VERSIONS.first(), newest.getString("protocolVersion"))
    }

    @Test fun listsToolsWithSchemas() {
        val list = rpc("tools/list")!!.getJSONObject("result").getJSONArray("tools")
        assertEquals(3, list.length())
        val echo = list.getJSONObject(0)
        val schema = echo.getJSONObject("inputSchema")
        assertEquals("object", schema.getString("type"))
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("text").getString("type"))
        assertEquals(1, schema.getJSONArray("required").length())
        assertTrue(echo.getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertFalse(list.getJSONObject(1).getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertFalse(list.getJSONObject(1).getJSONObject("inputSchema").has("required"))
        assertEquals("a", list.getJSONObject(2).getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("kind").getJSONArray("enum").getString(0))
    }

    @Test fun callsATool() {
        val r = rpc("tools/call", """{"name":"echo","arguments":{"text":"hi"}}""")!!.getJSONObject("result")
        assertFalse(r.getBoolean("isError"))
        assertEquals("hi", r.getJSONArray("content").getJSONObject(0).getString("text"))
        val img = rpc("tools/call", """{"name":"pick"}""")!!.getJSONObject("result").getJSONArray("content")
        assertEquals(1, img.length())
        assertEquals("image", img.getJSONObject(0).getString("type"))
    }

    @Test fun toolErrorsAreResultsNotProtocolErrors() {
        val r = rpc("tools/call", """{"name":"fail"}""")!!.getJSONObject("result")
        assertTrue(r.getBoolean("isError"))
        assertEquals("no node #3", r.getJSONArray("content").getJSONObject(0).getString("text"))
        val missing = rpc("tools/call", """{"name":"echo","arguments":{}}""")!!.getJSONObject("result")
        assertTrue(missing.getBoolean("isError"))
    }

    @Test fun protocolErrors() {
        assertEquals(-32601, rpc("nope")!!.getJSONObject("error").getInt("code"))
        assertEquals(-32602, rpc("tools/call", """{"name":"nope"}""")!!.getJSONObject("error").getInt("code"))
        assertEquals(-32700, JSONObject(p.handle("{bad")!!).getJSONObject("error").getInt("code"))
        assertNull(rpc("notifications/initialized", id = null))
        assertNull(p.handle("""{"jsonrpc":"2.0","id":5,"result":{}}"""))
    }

    @Test fun routeChecksTheClientAndTheToken() {
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        assertEquals(200, p.route(request(body = ping), true, token).first)
        assertEquals(403, p.route(request(body = ping), false, token).first)
        assertEquals(403, p.route(request(body = ping, origin = "http://evil.example"), true, token).first)
        assertEquals(401, p.route(request(body = ping, auth = null), true, token).first)
        assertEquals(401, p.route(request(body = ping, auth = "Bearer WRONG"), true, token).first)
        assertEquals(401, p.route(request(body = ping), true, "").first)
        assertEquals(404, p.route(request(path = "/other", body = ping), true, token).first)
        assertEquals(405, p.route(request(method = "GET"), true, token).first)
        assertEquals(202, p.route(request(body = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""), true, token).first)
    }
}
