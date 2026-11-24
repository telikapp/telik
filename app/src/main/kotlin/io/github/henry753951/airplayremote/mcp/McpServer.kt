package io.github.henry753951.airplayremote.mcp

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.henry753951.airplayremote.shortcuts.ShortcutApi
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The MCP server of spec 011 for agents on the local network. Off by default (FR-009). The log has the HTTP status,
 * the method and the tool name only, never the token or text (FR-013).
 */
class McpServer(private val context: Context) {
    private val workers = Executors.newFixedThreadPool(4) { Thread(it, "McpWorker") }
    @Volatile private var socket: ServerSocket? = null
    private val tools by lazy { TvTools(context.applicationContext, events) }
    private val protocol by lazy {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        McpProtocol(tools.all, version)
    }

    @Synchronized
    fun start() {
        if (socket != null) return
        val server = runCatching { ServerSocket(McpHttp.PORT) }.getOrElse {
            Log.w(TAG, "port ${McpHttp.PORT} busy: ${it.javaClass.simpleName}")
            return
        }
        socket = server
        running = true
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: SocketException) { break }
                workers.execute { client.use(::handle) }
            }
        }, "McpAccept").start()
        Log.i(TAG, "listening on ${McpHttp.PORT}")
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        if (socket != null) Log.i(TAG, "stopped")
        socket = null
        running = false
        tools.release()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val out = client.getOutputStream()
        val request = try {
            McpHttp.read(client.getInputStream())
        } catch (e: McpHttp.BadRequest) {
            Log.i(TAG, "bad request -> ${e.status}")
            runCatching { out.write(McpHttp.response(e.status)); out.flush() }
            return
        } catch (_: java.io.IOException) {
            return
        }
        val local = McpHttp.isLocal(client.inetAddress)
        val (status, body) = protocol.route(request, local, McpToken.get(context))
        Log.i(TAG, "${request.method} ${request.path} ${describe(request, status)} -> $status")
        runCatching { out.write(McpHttp.response(status, body)); out.flush() }
    }

    /** The JSON-RPC method and the tool name, for the log. No arguments: they can have typed text. */
    private fun describe(request: McpHttp.Request, status: Int): String {
        if (status == 401 || status == 403 || request.body.isEmpty()) return ""
        return runCatching {
            val msg = org.json.JSONObject(String(request.body, Charsets.UTF_8))
            val method = msg.optString("method")
            if (method == "tools/call") "tools/call:${msg.optJSONObject("params")?.optString("name")}" else method
        }.getOrDefault("")
    }

    companion object {
        private const val TAG = "McpServer"
        val events = TvEvents()
        @Volatile var running = false
            private set
        private val activities = ConcurrentHashMap<String, Boolean>()

        /** Called by the accessibility service for each event. Costs nothing while the server is off. */
        fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent) {
            if (!running) return
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = event.packageName?.toString() ?: return events.onEvent()
                val cls = event.className?.toString()
                val activity = cls?.takeIf { isActivity(service, pkg, it) }
                events.onWindow(pkg, activity)
            } else {
                events.onEvent()
            }
        }

        private fun isActivity(context: Context, pkg: String, cls: String): Boolean = activities.getOrPut("$pkg/$cls") {
            runCatching { context.packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
        }
    }
}

/** The token of the agent server: its own private file, apart from the Shortcuts token and the adb settings list. */
object McpToken {
    private const val FILE = "mcp"
    private const val KEY = "token"
    private val VALID = Regex("[A-Za-z0-9_-]{20,128}")

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        return renew(context)
    }

    fun renew(context: Context): String = ShortcutApi.newToken().also { set(context, it) }

    /** Spec 011 FR-014: a token from adb. Gives false for a value that is too short or has odd characters. */
    fun set(context: Context, token: String): Boolean {
        if (!VALID.matches(token)) return false
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, token).apply()
        return true
    }
}
