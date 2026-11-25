package io.github.henry753951.airplayremote.cloud

import android.content.Context
import android.util.Log
import io.github.henry753951.airplayremote.Prefs
import io.github.henry753951.airplayremote.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.ArrayDeque

/** What the settings show about the tunnel. */
data class CloudState(
    val status: Status = Status.OFF,
    val hostname: String? = null,
    val error: String? = null,
    val lines: List<String> = emptyList(),
) {
    enum class Status { OFF, NO_TOKEN, STARTING, CONNECTED, RETRYING }
}

/** The output of cloudflared, read line by line (spec 012 D2). Pure, with unit tests. */
object CloudLog {
    private val HOSTNAME = Regex("""hostname\\*"\s*:\s*\\*"([A-Za-z0-9.-]+\.[A-Za-z]{2,})""")

    fun connected(line: String) = line.contains("Registered tunnel connection")
    fun hostname(line: String): String? = if (line.contains("Updated to new configuration")) HOSTNAME.find(line)?.groupValues?.get(1) else null
    fun error(line: String): String? = if (line.contains(" ERR ")) line.substringAfter(" ERR ").trim().take(200) else null

    /** `--edge ip:port` arguments: cloudflared cannot resolve DNS in the app sandbox (spec 012 research). */
    fun edgeArgs(addresses: List<String>): List<String> = addresses.distinct().take(8).flatMap { listOf("--edge", "$it:$EDGE_PORT") }

    const val EDGE_PORT = 7844
    val EDGE_HOSTS = listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")

    /** Each restart waits twice as long, from 5 s up to 5 min. */
    fun backoffMs(attempt: Int): Long = (5_000L shl attempt.coerceIn(0, 6)).coerceAtMost(300_000L)
}

/**
 * Runs cloudflared from the native library folder while access from claude.ai is on (spec 012 D2). The token goes
 * through the environment, not the arguments. The log has the state only.
 */
object CloudTunnel {
    private const val TAG = "CloudTunnel"
    private const val MAX_LINES = 40
    private val _state = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState> = _state

    private var process: Process? = null
    private var runningToken: String? = null
    private var wanted = false
    private var attempt = 0
    private val lines = ArrayDeque<String>()
    private var restart: Thread? = null

    /** Starts, stops or restarts the tunnel to match the settings and the token. */
    @Synchronized
    fun sync(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val on = prefs[Prefs.MCP_ENABLED] && prefs[Prefs.CLOUD_ENABLED]
        val token = CloudToken.get(app)
        when {
            !on -> { stopLocked(); _state.value = CloudState() }
            token == null -> { stopLocked(); _state.value = CloudState(CloudState.Status.NO_TOKEN) }
            token != runningToken -> { stopLocked(); wanted = true; attempt = 0; startLocked(app, token) }
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
        _state.value = CloudState()
    }

    private fun stopLocked() {
        wanted = false
        restart?.interrupt()
        restart = null
        process?.destroy()
        process = null
        runningToken = null
    }

    private fun startLocked(context: Context, token: String) {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!binary.canExecute()) {
            _state.value = CloudState(CloudState.Status.RETRYING, error = "cloudflared is missing in this build")
            return
        }
        _state.value = CloudState(CloudState.Status.STARTING, hostname = _state.value.hostname)
        runningToken = token
        // DNS needs the network: not on the main thread.
        Thread({
            val edges = edges()
            synchronized(this) { if (wanted && runningToken == token && process == null) launchLocked(context, binary, token, edges) }
        }, "CloudTunnelStart").start()
    }

    /**
     * The edge addresses from the system resolver. In the app sandbox the Go resolver of cloudflared cannot read the
     * resolv.conf of the TV vendor, so it asks localhost and fails.
     */
    private fun edges(): List<String> = CloudLog.EDGE_HOSTS.flatMap { host ->
        runCatching { java.net.InetAddress.getAllByName(host).filterIsInstance<java.net.Inet4Address>().take(4).mapNotNull { it.hostAddress } }
            .getOrDefault(emptyList())
    }

    private fun launchLocked(context: Context, binary: File, token: String, edges: List<String>) {
        val home = File(context.filesDir, "cloudflared").apply { mkdirs() }
        val args = listOf(binary.path, "tunnel", "--no-autoupdate", "--loglevel", "info") + CloudLog.edgeArgs(edges) + "run"
        val pb = ProcessBuilder(args)
            .directory(home)
            .redirectErrorStream(true)
        pb.environment().apply {
            put("TUNNEL_TOKEN", token)
            put("HOME", home.path)
            // Go does not know the Android CA folder (spec 012 research).
            put("SSL_CERT_DIR", "/system/etc/security/cacerts")
        }
        val p = try {
            pb.start()
        } catch (e: java.io.IOException) {
            _state.value = CloudState(CloudState.Status.RETRYING, error = "cannot start cloudflared: ${e.javaClass.simpleName}")
            runningToken = null
            return
        }
        process = p
        lines.clear()
        Log.i(TAG, "started with ${edges.size} edge addresses")
        Thread({ read(context, p) }, "CloudTunnelOut").start()
    }

    private fun read(context: Context, p: Process) {
        p.inputStream.bufferedReader().useLines { seq ->
            for (line in seq) synchronized(this) {
                if (process !== p) return
                lines.addLast(line.take(300))
                while (lines.size > MAX_LINES) lines.removeFirst()
                val s = _state.value
                _state.value = s.copy(
                    status = if (CloudLog.connected(line)) CloudState.Status.CONNECTED.also { attempt = 0 } else s.status,
                    hostname = CloudLog.hostname(line) ?: s.hostname,
                    error = CloudLog.error(line) ?: s.error,
                    lines = lines.toList(),
                )
            }
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        synchronized(this) {
            if (process !== p) return
            process = null
            val token = runningToken
            runningToken = null
            Log.i(TAG, "stopped with code $code")
            // cloudflared prints no token. Its last lines say why it stopped.
            if (code != 0) lines.toList().takeLast(6).forEach { Log.w(TAG, it) }
            if (!wanted || token == null) return
            val delay = CloudLog.backoffMs(attempt++)
            _state.value = _state.value.copy(status = CloudState.Status.RETRYING)
            restart = Thread({
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return@Thread }
                synchronized(this) { if (wanted && process == null && runningToken == null) startLocked(context, token) }
            }, "CloudTunnelRestart").also { it.start() }
        }
    }
}
