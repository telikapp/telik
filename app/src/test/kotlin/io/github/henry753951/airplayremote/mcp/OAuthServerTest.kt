package io.github.henry753951.airplayremote.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class OAuthServerTest {
    private val base = "https://tv.example.com"
    private val callback = "https://claude.ai/api/mcp/auth_callback"
    private var now = 1_000_000L
    private val memory = object : OAuthStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }
    private val shown = mutableListOf<OAuthServer.Pending>()
    private fun server() = OAuthServer(memory, { now }).also { s -> s.onPending = { shown += it } }
    private val s = server()

    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private fun register(redirect: String = callback): String {
        val (status, j) = s.register("""{"client_name":"Claude","redirect_uris":["$redirect"]}""")
        assertEquals(201, status)
        return j.getString("client_id")
    }

    private fun authorize(client: String, extra: Map<String, String> = emptyMap()) = s.authorize(mapOf(
        "response_type" to "code", "client_id" to client, "redirect_uri" to callback, "state" to "xyz",
        "code_challenge" to OAuthServer.s256(verifier), "code_challenge_method" to "S256", "resource" to "$base/mcp",
    ) + extra, base)

    /** Registers, authorizes, allows on the TV, and gives the code from the redirect. */
    private fun code(client: String = register()): Pair<String, String> {
        val p = (authorize(client) as OAuthServer.Start.Ok).pending
        s.answer(p.id, allow = true)
        val (redirect, error) = s.poll(p.id)
        assertNull(error)
        val q = McpHttp.form(URI(redirect).rawQuery)
        assertEquals("xyz", q["state"])
        return client to q.getValue("code")
    }

    private fun exchange(client: String, code: String, v: String = verifier) = s.token(mapOf(
        "grant_type" to "authorization_code", "code" to code, "client_id" to client, "redirect_uri" to callback, "code_verifier" to v,
    ))

    @Test fun pkceMatchesTheRfcExample() {
        // RFC 7636 appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuthServer.s256(verifier))
    }

    @Test fun metadataPointsToThisServer() {
        val m = s.metadata(base)
        assertEquals("$base/token", m.getString("token_endpoint"))
        assertEquals("S256", m.getJSONArray("code_challenge_methods_supported").getString(0))
        assertEquals("$base/mcp", s.protectedResource(base).getString("resource"))
        assertEquals(base, s.protectedResource(base).getJSONArray("authorization_servers").getString(0))
    }

    @Test fun onlyClaudeOrLoopbackRedirects() {
        assertTrue(OAuthServer.allowedRedirect(callback))
        assertTrue(OAuthServer.allowedRedirect("https://claude.com/api/mcp/auth_callback"))
        assertTrue(OAuthServer.allowedRedirect("http://localhost:33418/callback"))
        assertTrue(OAuthServer.allowedRedirect("http://127.0.0.1:5000/cb"))
        assertFalse(OAuthServer.allowedRedirect("https://evil.example/api/mcp/auth_callback"))
        assertFalse(OAuthServer.allowedRedirect("https://claude.ai.evil.example/api/mcp/auth_callback"))
        assertFalse(OAuthServer.allowedRedirect("http://localhost.evil.example/cb"))
        assertFalse(OAuthServer.allowedRedirect("https://localhost/cb"))
        assertEquals(400, s.register("""{"redirect_uris":["https://evil.example/cb"]}""").first)
        assertEquals(400, s.register("not json").first)
    }

    @Test fun fullFlowGivesAWorkingAccessToken() {
        val (client, code) = code()
        assertEquals(1, shown.size)
        val (status, j) = exchange(client, code)
        assertEquals(200, status)
        assertTrue(s.valid("Bearer " + j.getString("access_token")))
        assertFalse(s.valid("Bearer nope"))
        assertFalse(s.valid(null))
        assertEquals(3600, j.getInt("expires_in"))
    }

    @Test fun aCodeWorksOnceAndNeedsTheVerifier() {
        val (client, code) = code()
        assertEquals("invalid_grant", exchange(client, code, "wrong-verifier-wrong-verifier-wrong-verifier").second.getString("error"))
        // The failed try used the code up.
        assertEquals(400, exchange(client, code).first)
        val (client2, code2) = code()
        assertEquals(200, exchange(client2, code2).first)
        assertEquals(400, exchange(client2, code2).first)
    }

    @Test fun aCodeExpires() {
        val (client, code) = code()
        now += OAuthServer.CODE_MS + 1
        assertEquals(400, exchange(client, code).first)
    }

    @Test fun denyAndTimeoutFail() {
        val client = register()
        val p = (authorize(client) as OAuthServer.Start.Ok).pending
        s.answer(p.id, allow = false)
        val (redirect, error) = s.poll(p.id)
        assertNotNull(error)
        assertTrue(redirect!!.contains("error=access_denied"))
        val p2 = (authorize(client) as OAuthServer.Start.Ok).pending
        now += OAuthServer.CONFIRM_MS + 1
        assertEquals(null to "no answer on the TV in time", s.poll(p2.id))
        s.answer(p2.id, allow = true)
        assertEquals(null to "unknown or old request", s.poll(p2.id))
    }

    @Test fun badRequestsGoBackOrStayOnThePage() {
        val client = register()
        assertTrue(s.authorize(mapOf("client_id" to "nope"), base) is OAuthServer.Start.Page)
        assertTrue(authorize(client, mapOf("redirect_uri" to "https://evil.example/cb")) is OAuthServer.Start.Page)
        val noPkce = authorize(client, mapOf("code_challenge_method" to "plain"))
        assertTrue((noPkce as OAuthServer.Start.Redirect).url.contains("error=invalid_request"))
        assertTrue(authorize(client, mapOf("resource" to "https://other.example/mcp")) is OAuthServer.Start.Redirect)
    }

    @Test fun atMostThreeWaitOnTheTv() {
        val client = register()
        repeat(OAuthServer.MAX_PENDING) { assertTrue(authorize(client) is OAuthServer.Start.Ok) }
        assertTrue((authorize(client) as OAuthServer.Start.Redirect).url.contains("temporarily_unavailable"))
    }

    @Test fun refreshRotatesAndAccessExpires() {
        val (client, code) = code()
        val first = exchange(client, code).second
        val (status, second) = s.token(mapOf("grant_type" to "refresh_token", "refresh_token" to first.getString("refresh_token"), "client_id" to client))
        assertEquals(200, status)
        assertNotEquals(first.getString("refresh_token"), second.getString("refresh_token"))
        // The old refresh token is used up.
        assertEquals("invalid_grant", s.token(mapOf("grant_type" to "refresh_token", "refresh_token" to first.getString("refresh_token"))).second.getString("error"))
        now += OAuthServer.ACCESS_MS + 1
        assertFalse(s.valid("Bearer " + second.getString("access_token")))
        assertEquals(200, s.token(mapOf("grant_type" to "refresh_token", "refresh_token" to second.getString("refresh_token"))).first)
    }

    @Test fun stateSurvivesARestartWithHashesOnly() {
        val (client, code) = code()
        val token = exchange(client, code).second.getString("access_token")
        assertFalse(memory.json!!.contains(token))
        assertTrue(server().valid("Bearer $token"))
    }

    @Test fun revokeAllEndsEverything() {
        val (client, code) = code()
        val j: JSONObject = exchange(client, code).second
        s.revokeAll()
        assertFalse(s.valid("Bearer " + j.getString("access_token")))
        assertEquals(400, s.token(mapOf("grant_type" to "refresh_token", "refresh_token" to j.getString("refresh_token"))).first)
        assertEquals(0, s.clientCount())
    }
}
