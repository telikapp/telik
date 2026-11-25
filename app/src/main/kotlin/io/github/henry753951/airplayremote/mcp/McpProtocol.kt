package io.github.henry753951.airplayremote.mcp

import io.github.henry753951.airplayremote.shortcuts.ShortcutApi
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The answer of a tool: text parts, an optional image, and the error flag of MCP. */
class ToolResult(val text: String, val image: Pair<String, String>? = null, val isError: Boolean = false) {
    fun toJson(): JSONObject {
        val content = JSONArray()
        if (text.isNotEmpty() || image == null) content.put(JSONObject().put("type", "text").put("text", text))
        image?.let { (mime, base64) -> content.put(JSONObject().put("type", "image").put("mimeType", mime).put("data", base64)) }
        return JSONObject().put("content", content).put("isError", isError)
    }

    companion object {
        fun error(text: String) = ToolResult(text, isError = true)
    }
}

/** An error that a tool gives back to the agent as text, not as a protocol error. */
class ToolError(message: String) : Exception(message)

/**
 * One MCP tool. [params] is a compact schema: name to "type:description", a `?` after the name for an optional
 * parameter, for example `"node?" to "integer:id from screen"`. The descriptions stay short, to save agent tokens.
 */
class McpTool(
    val name: String,
    val description: String,
    val params: List<Pair<String, String>> = emptyList(),
    val action: Boolean = false,
    val run: (JSONObject) -> ToolResult,
) {
    fun schema(): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        for ((raw, spec) in params) {
            val optional = raw.endsWith("?")
            val key = raw.removeSuffix("?")
            val type = spec.substringBefore(':')
            val desc = spec.substringAfter(':', "")
            val p = JSONObject()
            if (type.startsWith("enum(")) {
                p.put("type", "string").put("enum", JSONArray(type.removePrefix("enum(").removeSuffix(")").split('|')))
            } else {
                p.put("type", type)
            }
            if (desc.isNotEmpty()) p.put("description", desc)
            props.put(key, p)
            if (!optional) required.put(key)
        }
        val s = JSONObject().put("type", "object").put("properties", props)
        if (required.length() > 0) s.put("required", required)
        return s
    }
}

/** JSON-RPC 2.0 and the MCP methods (spec 011 D2): pure, so it has unit tests. */
class McpProtocol(private val tools: List<McpTool>, private val version: String) {
    private val byName = tools.associateBy { it.name }

    /** The HTTP answer for one request: the status and the body (null for an empty body). */
    fun route(request: McpHttp.Request, clientLocal: Boolean, token: String): Pair<Int, String?> =
        route(request, clientLocal, remote = false) { ShortcutApi.authorized(it, token) }

    /**
     * [remote] is a request through the tunnel of spec 012. It needs a token like a local one, but the Origin rule
     * is for the local network only: a web page cannot reach the TV through the public host without a token.
     */
    fun route(request: McpHttp.Request, clientLocal: Boolean, remote: Boolean, authorized: (String?) -> Boolean): Pair<Int, String?> {
        if (!clientLocal) return 403 to error(null, -32001, "local network only")
        if (!remote && request.header("origin") != null) return 403 to error(null, -32001, "browser requests are not allowed")
        if (request.path != McpHttp.PATH) return 404 to null
        if (!authorized(request.header("authorization"))) return 401 to error(null, -32001, "unauthorized")
        if (request.method != "POST") return 405 to null
        val body = handle(String(request.body, Charsets.UTF_8)) ?: return 202 to null
        return 200 to body
    }

    /** Handles one JSON-RPC message. Gives null for a notification or a response. */
    fun handle(text: String): String? {
        val msg = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return error(null, -32700, "parse error")
        }
        val id = msg.opt("id")?.takeIf { it != JSONObject.NULL }
        val method = msg.optString("method", "")
        if (method.isEmpty()) return if (id == null || msg.has("result") || msg.has("error")) null else error(id, -32600, "invalid request")
        if (id == null) return null
        val params = msg.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", JSONArray(tools.map(::describe))))
            "tools/call" -> call(id, params)
            "resources/list" -> result(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> result(id, JSONObject().put("prompts", JSONArray()))
            else -> error(id, -32601, "method not found: $method")
        }
    }

    private fun initialize(params: JSONObject): JSONObject {
        val asked = params.optString("protocolVersion", "")
        return JSONObject()
            .put("protocolVersion", if (asked in VERSIONS) asked else VERSIONS.first())
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "telik").put("title", "Telik TV").put("version", version))
            .put("instructions", INSTRUCTIONS)
    }

    private fun describe(t: McpTool) = JSONObject()
        .put("name", t.name)
        .put("description", t.description)
        .put("inputSchema", t.schema())
        .put("annotations", JSONObject().put("readOnlyHint", !t.action).put("openWorldHint", false))

    private fun call(id: Any, params: JSONObject): String {
        val name = params.optString("name", "")
        val tool = byName[name] ?: return error(id, -32602, "unknown tool: $name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val out = try {
            tool.run(args)
        } catch (e: ToolError) {
            ToolResult.error(e.message ?: "error")
        } catch (e: RuntimeException) {
            ToolResult.error("${e.javaClass.simpleName}: ${e.message}")
        }
        return result(id, out.toJson())
    }

    private fun result(id: Any, r: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r).toString()

    private fun error(id: Any?, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message)).toString()

    companion object {
        val VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        const val INSTRUCTIONS =
            "Controls an Android TV with structured data: screen/find/act, apps/app_info/open, media, tv_rows. " +
                "Action tools wait until the screen is quiet and give v=<screen version>. Node ids come from the last screen or find. " +
                "Some apps (YouTube, Apple TV) give no tree: use recipe, deep links (open), media and key there."
    }
}

/** Reads typed arguments, with a [ToolError] for a missing or wrong value. */
fun JSONObject.str(key: String): String = optString(key, "").takeIf { has(key) && it.isNotEmpty() } ?: throw ToolError("missing $key")
fun JSONObject.strOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) (opt(key) as? Number)?.toInt() ?: optString(key).toIntOrNull() ?: throw ToolError("$key must be a number") else null
fun JSONObject.boolOr(key: String, def: Boolean): Boolean = if (has(key) && !isNull(key)) optBoolean(key, def) else def
