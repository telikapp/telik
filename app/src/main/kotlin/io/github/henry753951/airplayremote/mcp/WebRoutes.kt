package io.github.henry753951.airplayremote.mcp

import io.github.henry753951.airplayremote.cloud.SetupCode
import org.json.JSONObject

/** One HTTP answer. */
data class WebResponse(val status: Int, val body: String?, val type: String = "application/json", val headers: Map<String, String> = emptyMap())

/**
 * The web paths of spec 012 next to `/mcp`: the OAuth metadata and endpoints for claude.ai, and the local setup
 * page for the tunnel token. Gives null for other paths. Pure, with unit tests.
 */
class WebRoutes(
    private val oauth: OAuthServer,
    private val setup: SetupCode,
    private val saveToken: (String) -> Boolean,
) {
    /** [remote]: the request came through the tunnel. [base]: `https://host` or `http://host:port`. */
    fun handle(r: McpHttp.Request, remote: Boolean, base: String): WebResponse? = when {
        r.path == "/.well-known/oauth-protected-resource" || r.path == "/.well-known/oauth-protected-resource${McpHttp.PATH}" ->
            get(r) { json(200, oauth.protectedResource(base)) }
        r.path == "/.well-known/oauth-authorization-server" || r.path == "/.well-known/oauth-authorization-server${McpHttp.PATH}" ->
            get(r) { json(200, oauth.metadata(base)) }
        r.path == "/register" -> post(r) { oauth.register(String(r.body, Charsets.UTF_8)).let { (s, j) -> json(s, j) } }
        r.path == "/authorize" -> get(r) { authorize(r, base) }
        r.path == "/authorize/wait" -> get(r) { wait(r.params()["id"].orEmpty()) }
        r.path == "/token" -> post(r) { oauth.token(r.form()).let { (s, j) -> json(s, j, mapOf("Pragma" to "no-cache")) } }
        r.path == "/setup" -> if (remote) WebResponse(404, null) else setupPage(r)
        else -> null
    }

    /** The 401 header of spec 012 D3 that sends claude.ai to the metadata. */
    fun challenge(base: String) = mapOf("WWW-Authenticate" to "Bearer resource_metadata=\"$base/.well-known/oauth-protected-resource${McpHttp.PATH}\"")

    private fun get(r: McpHttp.Request, ok: () -> WebResponse) = if (r.method == "GET") ok() else WebResponse(405, null)
    private fun post(r: McpHttp.Request, ok: () -> WebResponse) = if (r.method == "POST") ok() else WebResponse(405, null)
    private fun json(status: Int, j: JSONObject, extra: Map<String, String> = emptyMap()) = WebResponse(status, j.toString(), headers = extra)

    private fun authorize(r: McpHttp.Request, base: String): WebResponse = when (val s = oauth.authorize(r.params(), base)) {
        is OAuthServer.Start.Redirect -> WebResponse(302, null, headers = mapOf("Location" to s.url))
        is OAuthServer.Start.Page -> html(400, page("Telik", "<p>${esc(s.message)}</p>"))
        is OAuthServer.Start.Ok -> html(200, waitPage(s.pending))
    }

    private fun wait(id: String): WebResponse {
        val (redirect, error) = oauth.poll(id)
        return json(200, JSONObject().apply {
            when {
                redirect != null -> put("redirect", redirect)
                error != null -> put("error", error)
                else -> put("wait", true)
            }
        })
    }

    private fun setupPage(r: McpHttp.Request): WebResponse {
        if (r.method == "GET") return html(200, setupForm(r.params()["code"].orEmpty()))
        if (r.method != "POST") return WebResponse(405, null)
        val f = r.form()
        if (!setup.use(f["code"])) return html(403, page("Telik", "<p>The code is wrong or old. Open the QR code on the TV again.</p>"))
        return if (saveToken(f["token"].orEmpty())) {
            html(200, page("Telik", "<p>Saved. The TV starts the tunnel now. You can close this page.</p>"))
        } else {
            html(400, page("Telik", "<p>This is not a Cloudflare tunnel token. Open the QR code on the TV again.</p>"))
        }
    }

    private fun html(status: Int, body: String) = WebResponse(status, body, "text/html; charset=utf-8",
        mapOf("Content-Security-Policy" to "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'self'",
            "X-Frame-Options" to "DENY"))

    private fun waitPage(p: OAuthServer.Pending) = page("Telik: confirm on the TV", """
        <p>Confirm on the TV. Check that it shows this code:</p>
        <p class="code">${esc(p.display)}</p>
        <p id="s">Waiting for the TV…</p>
        <script>
        (function poll(){
          fetch('/authorize/wait?id=${esc(p.id)}').then(function(r){return r.json()}).then(function(j){
            if (j.redirect) { location.replace(j.redirect); return; }
            if (j.error) { document.getElementById('s').textContent = j.error; return; }
            setTimeout(poll, 1000);
          }).catch(function(){ setTimeout(poll, 2000); });
        })();
        </script>""")

    private fun setupForm(code: String) = page("Telik: tunnel token", """
        <p>Paste the token of your Cloudflare tunnel. In the Cloudflare dashboard it is in the install command of the
        tunnel, after <code>--token</code>.</p>
        <form method="post" action="/setup">
          <input type="hidden" name="code" value="${esc(code)}">
          <textarea name="token" rows="6" required autocomplete="off" spellcheck="false"></textarea>
          <button type="submit">Save</button>
        </form>""")

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title>
        <style>body{font:17px system-ui,sans-serif;max-width:34em;margin:2em auto;padding:0 1em;background:#111;color:#eee}
        .code{font-size:3em;letter-spacing:.2em;font-weight:600}textarea{width:100%;font:14px monospace}
        button{font-size:1.1em;padding:.5em 1.5em;margin-top:1em}</style></head>
        <body><h2>${esc(title)}</h2>$body</body></html>"""

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
