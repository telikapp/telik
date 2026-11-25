package io.github.henry753951.airplayremote.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Where the OAuth state lives. Telik keeps it in private preferences as JSON. */
interface OAuthStore {
    fun load(): String?
    fun save(json: String)
}

/**
 * The OAuth 2.1 server of spec 012 D4 for claude.ai: metadata, dynamic client registration, the authorization code
 * grant with PKCE S256 and a confirmation on the TV, and rotating refresh tokens. Only SHA-256 hashes of tokens are
 * kept (FR-008). Pure, with a clock and a random source for unit tests.
 */
class OAuthServer(
    private val store: OAuthStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    data class Client(val id: String, val name: String, val redirects: List<String>, val created: Long)
    private data class Grant(val clientId: String, val until: Long)
    private data class Code(val clientId: String, val redirect: String, val challenge: String, val until: Long)
    data class Pending(val id: String, val display: String, val clientName: String, val until: Long) {
        @Volatile var result: String? = null
        @Volatile var error: String? = null
    }

    sealed interface Start {
        data class Ok(val pending: Pending) : Start
        /** An error that goes back to the client by its redirect address. */
        data class Redirect(val url: String) : Start
        /** An error that shows on the page: the redirect address cannot be trusted. */
        data class Page(val message: String) : Start
    }

    private val clients = LinkedHashMap<String, Client>()
    private val access = HashMap<String, Grant>()
    private val refresh = HashMap<String, Grant>()
    private val codes = HashMap<String, Code>()
    private val pending = LinkedHashMap<String, Pending>()
    private val requests = HashMap<String, Triple<Client, String, Pair<String, String?>>>()

    /** Called when a confirmation must show on the TV. */
    @Volatile var onPending: (Pending) -> Unit = {}

    init { load() }

    // ---- Metadata ----

    fun protectedResource(base: String): JSONObject = JSONObject()
        .put("resource", "$base${McpHttp.PATH}")
        .put("authorization_servers", JSONArray().put(base))
        .put("bearer_methods_supported", JSONArray().put("header"))
        .put("scopes_supported", JSONArray().put(SCOPE))
        .put("resource_name", "Telik TV")

    fun metadata(base: String): JSONObject = JSONObject()
        .put("issuer", base)
        .put("authorization_endpoint", "$base/authorize")
        .put("token_endpoint", "$base/token")
        .put("registration_endpoint", "$base/register")
        .put("response_types_supported", JSONArray().put("code"))
        .put("grant_types_supported", JSONArray().put("authorization_code").put("refresh_token"))
        .put("code_challenge_methods_supported", JSONArray().put("S256"))
        .put("token_endpoint_auth_methods_supported", JSONArray().put("none"))
        .put("scopes_supported", JSONArray().put(SCOPE))

    // ---- Registration (RFC 7591) ----

    @Synchronized
    fun register(body: String): Pair<Int, JSONObject> {
        val req = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to err("invalid_client_metadata", "body is not JSON")
        val list = req.optJSONArray("redirect_uris") ?: return 400 to err("invalid_redirect_uri", "redirect_uris is missing")
        val redirects = (0 until list.length()).map { list.optString(it) }
        if (redirects.isEmpty() || !redirects.all(::allowedRedirect)) {
            return 400 to err("invalid_redirect_uri", "only the Claude callback or a loopback address")
        }
        val name = req.optString("client_name", "MCP client").take(60).ifBlank { "MCP client" }
        val client = Client(token(16), name, redirects, clock())
        clients[client.id] = client
        while (clients.size > MAX_CLIENTS) clients.remove(clients.keys.first())
        save()
        return 201 to JSONObject()
            .put("client_id", client.id)
            .put("client_name", client.name)
            .put("redirect_uris", JSONArray(redirects))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("client_id_issued_at", client.created / 1000)
    }

    // ---- Authorization ----

    /** Checks an authorization request and starts a confirmation on the TV. */
    @Synchronized
    fun authorize(q: Map<String, String>, base: String): Start {
        val client = clients[q["client_id"]] ?: return Start.Page("unknown client: connect again from the app")
        val redirect = q["redirect_uri"] ?: client.redirects.singleOrNull() ?: return Start.Page("redirect_uri is missing")
        if (redirect !in client.redirects || !allowedRedirect(redirect)) return Start.Page("redirect_uri is not registered")
        val state = q["state"]
        fun fail(code: String, text: String) = Start.Redirect(withQuery(redirect, "error" to code, "error_description" to text, "state" to state))
        if (q["response_type"] != "code") return fail("unsupported_response_type", "only code")
        val challenge = q["code_challenge"]
        if (challenge.isNullOrEmpty() || q["code_challenge_method"] != "S256") return fail("invalid_request", "PKCE S256 is required")
        q["resource"]?.let { if (it.trimEnd('/') != "$base${McpHttp.PATH}" && it.trimEnd('/') != base) return fail("invalid_target", "unknown resource") }
        expire()
        if (pending.size >= MAX_PENDING) return fail("temporarily_unavailable", "too many requests wait on the TV")
        val p = Pending(token(12), (random.nextInt(9000) + 1000).toString(), client.name, clock() + CONFIRM_MS)
        pending[p.id] = p
        requests[p.id] = Triple(client, redirect, challenge to state)
        onPending(p)
        return Start.Ok(p)
    }

    /** The answer of the TV: OK or Back. */
    @Synchronized
    fun answer(id: String, allow: Boolean) {
        val p = pending[id] ?: return
        val (client, redirect, cs) = requests[id] ?: return
        val (challenge, state) = cs
        if (p.result != null || p.error != null) return
        if (clock() > p.until) { p.error = "the request expired"; return }
        if (!allow) {
            p.error = "the TV denied the access"
            p.result = withQuery(redirect, "error" to "access_denied", "state" to state)
            return
        }
        val code = token(32)
        codes[sha(code)] = Code(client.id, redirect, challenge, clock() + CODE_MS)
        p.result = withQuery(redirect, "code" to code, "state" to state)
    }

    /** What the waiting page gets: the redirect address, an error, or null while it waits. */
    @Synchronized
    fun poll(id: String): Pair<String?, String?> {
        val p = pending[id] ?: return null to "unknown or old request"
        if (p.result != null) {
            pending.remove(id); requests.remove(id)
            return p.result to p.error
        }
        if (clock() > p.until) {
            pending.remove(id); requests.remove(id)
            return null to "no answer on the TV in time"
        }
        return null to null
    }

    @Synchronized
    fun pendingRequest(id: String): Pending? = pending[id]

    // ---- Tokens ----

    @Synchronized
    fun token(form: Map<String, String>): Pair<Int, JSONObject> {
        expire()
        return when (form["grant_type"]) {
            "authorization_code" -> {
                val code = codes.remove(sha(form["code"].orEmpty())) ?: return 400 to err("invalid_grant", "unknown or used code")
                if (clock() > code.until) return 400 to err("invalid_grant", "the code expired")
                if (form["client_id"] != null && form["client_id"] != code.clientId) return 400 to err("invalid_grant", "client mismatch")
                if (form["redirect_uri"] != null && form["redirect_uri"] != code.redirect) return 400 to err("invalid_grant", "redirect_uri mismatch")
                val verifier = form["code_verifier"] ?: return 400 to err("invalid_request", "code_verifier is missing")
                if (s256(verifier) != code.challenge) return 400 to err("invalid_grant", "PKCE check failed")
                200 to issue(code.clientId)
            }
            "refresh_token" -> {
                val grant = refresh.remove(sha(form["refresh_token"].orEmpty())) ?: return 400 to err("invalid_grant", "unknown refresh token")
                if (clock() > grant.until || grant.clientId !in clients) { save(); return 400 to err("invalid_grant", "the refresh token expired") }
                if (form["client_id"] != null && form["client_id"] != grant.clientId) { save(); return 400 to err("invalid_grant", "client mismatch") }
                200 to issue(grant.clientId)
            }
            else -> 400 to err("unsupported_grant_type", "authorization_code or refresh_token")
        }
    }

    private fun issue(clientId: String): JSONObject {
        val a = token(32)
        val r = token(32)
        access[sha(a)] = Grant(clientId, clock() + ACCESS_MS)
        refresh[sha(r)] = Grant(clientId, clock() + REFRESH_MS)
        save()
        return JSONObject()
            .put("access_token", a)
            .put("token_type", "Bearer")
            .put("expires_in", ACCESS_MS / 1000)
            .put("refresh_token", r)
            .put("scope", SCOPE)
    }

    /** True for a live access token. */
    @Synchronized
    fun valid(bearer: String?): Boolean {
        val t = bearer?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val g = access[sha(t)] ?: return false
        return clock() <= g.until && g.clientId in clients
    }

    @Synchronized
    fun clientCount(): Int = clients.size

    /** Spec 012 FR-009: all grants and clients end. */
    @Synchronized
    fun revokeAll() {
        clients.clear(); access.clear(); refresh.clear(); codes.clear(); pending.clear(); requests.clear()
        save()
    }

    private fun expire() {
        val now = clock()
        access.entries.removeAll { it.value.until < now }
        refresh.entries.removeAll { it.value.until < now }
        codes.entries.removeAll { it.value.until < now }
        val old = pending.filterValues { it.until + CONFIRM_MS < now }.keys
        old.forEach { pending.remove(it); requests.remove(it) }
    }

    // ---- Storage ----

    private fun save() {
        val c = JSONArray()
        clients.values.forEach { c.put(JSONObject().put("id", it.id).put("name", it.name).put("redirects", JSONArray(it.redirects)).put("created", it.created)) }
        fun grants(m: Map<String, Grant>) = JSONObject().also { o -> m.forEach { (h, g) -> o.put(h, JSONObject().put("c", g.clientId).put("u", g.until)) } }
        store.save(JSONObject().put("clients", c).put("access", grants(access)).put("refresh", grants(refresh)).toString())
    }

    private fun load() {
        val json = store.load()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        json.optJSONArray("clients")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val r = o.getJSONArray("redirects")
                clients[o.getString("id")] = Client(o.getString("id"), o.optString("name"), (0 until r.length()).map(r::getString), o.optLong("created"))
            }
        }
        fun grants(o: JSONObject?, into: MutableMap<String, Grant>) = o?.keys()?.forEach { h ->
            val g = o.getJSONObject(h); into[h] = Grant(g.getString("c"), g.getLong("u"))
        }
        grants(json.optJSONObject("access"), access)
        grants(json.optJSONObject("refresh"), refresh)
    }

    private fun token(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        const val SCOPE = "tv"
        const val CONFIRM_MS = 2 * 60 * 1000L
        const val CODE_MS = 60 * 1000L
        const val ACCESS_MS = 60 * 60 * 1000L
        const val REFRESH_MS = 90L * 24 * 60 * 60 * 1000
        const val MAX_CLIENTS = 20
        const val MAX_PENDING = 3
        private val CLAUDE = setOf("https://claude.ai/api/mcp/auth_callback", "https://claude.com/api/mcp/auth_callback")

        /** FR-005: the Claude callback, or a loopback address for a local client such as Claude Code. */
        fun allowedRedirect(uri: String): Boolean {
            if (uri in CLAUDE) return true
            val u = runCatching { URI(uri) }.getOrNull() ?: return false
            if (u.scheme != "http" || u.rawFragment != null || u.userInfo != null) return false
            return u.host == "localhost" || u.host == "127.0.0.1" || u.host == "[::1]" || u.host == "::1"
        }

        fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun s256(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun err(code: String, text: String) = JSONObject().put("error", code).put("error_description", text)

        fun withQuery(base: String, vararg params: Pair<String, String?>): String {
            val q = params.filter { it.second != null }.joinToString("&") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }
            return if (q.isEmpty()) base else base + (if (base.contains('?')) "&" else "?") + q
        }
    }
}
