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
        // Spec 012 D3: cloudflared connects from the loopback address and adds Cf-Connecting-Ip.
        val remote = client.inetAddress.isLoopbackAddress && request.header("cf-connecting-ip") != null
        val host = request.header("host")?.takeIf { HOST.matches(it) } ?: "${client.localAddress.hostAddress}:${McpHttp.PORT}"
        val base = if (remote) "https://${host.substringBefore(':')}" else "http://$host"
        val routes = web(context)
        val page = if (local) routes.handle(request, remote, base) else null
        val answer = page ?: run {
            val token = McpToken.get(context)
            val oauth = oauth(context)
            val (status, body) = protocol.route(request, local, remote) { ShortcutApi.authorized(it, token) || oauth.valid(it) }
            WebResponse(status, body, headers = if (status == 401) routes.challenge(base) else emptyMap())
        }
        Log.i(TAG, "${if (remote) "remote " else ""}${request.method} ${request.path} ${describe(request, answer.status)} -> ${answer.status}")
        runCatching { out.write(McpHttp.response(answer.status, answer.body, answer.type, answer.headers)); out.flush() }
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
        private val HOST = Regex("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?")
        val setupCode = io.github.henry753951.airplayremote.cloud.SetupCode()
        @Volatile private var oauthServer: OAuthServer? = null
        @Volatile private var webRoutes: WebRoutes? = null

        /** The OAuth server of spec 012, with its state in a private file. */
        @Synchronized
        fun oauth(context: Context): OAuthServer = oauthServer ?: run {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("oauth", Context.MODE_PRIVATE)
            OAuthServer(object : OAuthStore {
                override fun load() = prefs.getString("state", null)
                override fun save(json: String) { prefs.edit().putString("state", json).apply() }
            }).also { s ->
                s.onPending = { p -> AgentApprove.show(app, p) }
                oauthServer = s
            }
        }

        @Synchronized
        private fun web(context: Context): WebRoutes = webRoutes ?: WebRoutes(oauth(context), setupCode) { token ->
            val app = context.applicationContext
            io.github.henry753951.airplayremote.cloud.CloudToken.set(app, token).also { ok ->
                if (ok) io.github.henry753951.airplayremote.cloud.CloudTunnel.sync(app)
            }
        }.also { webRoutes = it }

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
