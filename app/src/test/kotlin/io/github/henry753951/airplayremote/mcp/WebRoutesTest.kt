package io.github.henry753951.airplayremote.mcp

import io.github.henry753951.airplayremote.cloud.SetupCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRoutesTest {
    private val base = "https://tv.example.com"
    private val store = object : OAuthStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }
    private val oauth = OAuthServer(store)
    private var saved: String? = null
    private val setup = SetupCode()
    private val routes = WebRoutes(oauth, setup) { t -> (t.length > 3).also { if (it) saved = t } }

    private fun req(method: String, path: String, query: String = "", body: String = "") =
        McpHttp.Request(method, path, emptyMap(), body.toByteArray(), query)

    @Test fun servesTheMetadata() {
        val r = routes.handle(req("GET", "/.well-known/oauth-protected-resource/mcp"), remote = true, base = base)!!
        assertEquals(200, r.status)
        assertEquals("$base/mcp", JSONObject(r.body!!).getString("resource"))
        val m = routes.handle(req("GET", "/.well-known/oauth-authorization-server"), true, base)!!
        assertEquals("$base/authorize", JSONObject(m.body!!).getString("authorization_endpoint"))
        assertEquals(405, routes.handle(req("POST", "/.well-known/oauth-authorization-server"), true, base)!!.status)
        assertNull(routes.handle(req("POST", "/mcp"), true, base))
        assertTrue(routes.challenge(base).getValue("WWW-Authenticate").contains("resource_metadata=\"$base/.well-known/oauth-protected-resource/mcp\""))
    }

    @Test fun authorizeShowsTheWaitPageAndRedirectsAfterOk() {
        val reg = routes.handle(req("POST", "/register", body = """{"redirect_uris":["https://claude.ai/api/mcp/auth_callback"]}"""), true, base)!!
        assertEquals(201, reg.status)
        val client = JSONObject(reg.body!!).getString("client_id")
        var shown: OAuthServer.Pending? = null
        oauth.onPending = { shown = it }
        val q = "response_type=code&client_id=$client&redirect_uri=https%3A%2F%2Fclaude.ai%2Fapi%2Fmcp%2Fauth_callback" +
            "&code_challenge=${OAuthServer.s256("v".repeat(43))}&code_challenge_method=S256&state=s1"
        val page = routes.handle(req("GET", "/authorize", q), true, base)!!
        assertEquals(200, page.status)
        assertTrue(page.type.startsWith("text/html"))
        assertTrue(page.body!!.contains(shown!!.display))
        assertTrue(JSONObject(routes.handle(req("GET", "/authorize/wait", "id=${shown!!.id}"), true, base)!!.body!!).getBoolean("wait"))
        oauth.answer(shown!!.id, true)
        val done = JSONObject(routes.handle(req("GET", "/authorize/wait", "id=${shown!!.id}"), true, base)!!.body!!)
        assertTrue(done.getString("redirect").startsWith("https://claude.ai/api/mcp/auth_callback?code="))
        val bad = routes.handle(req("GET", "/authorize", "client_id=$client&response_type=token"), true, base)!!
        assertEquals(302, bad.status)
        assertTrue(bad.headers.getValue("Location").contains("unsupported_response_type"))
    }

    @Test fun setupNeedsTheCodeAndIsLocalOnly() {
        assertEquals(404, routes.handle(req("GET", "/setup"), remote = true, base = base)!!.status)
        assertEquals(200, routes.handle(req("GET", "/setup", "code=123"), remote = false, base = "http://tv:8766")!!.status)
        val code = setup.renew()
        assertEquals(403, routes.handle(req("POST", "/setup", body = "code=000000&token=abcdef"), false, "http://tv:8766")!!.status)
        assertNull(saved)
        assertEquals(200, routes.handle(req("POST", "/setup", body = "code=$code&token=abcdef"), false, "http://tv:8766")!!.status)
        assertEquals("abcdef", saved)
        // The code works once.
        assertEquals(403, routes.handle(req("POST", "/setup", body = "code=$code&token=other"), false, "http://tv:8766")!!.status)
    }
}
