# Plan: The TV as a claude.ai connector

**Spec**: `spec.md` | **Branch**: `andrewkomkov/tv-cloud`, stacked on `andrewkomkov/tv-mcp-data`

## Technical context

- The MCP server, the tools and the token of spec 011 stay. This feature adds a public path to the same server.
- Test TV: Android 14, armeabi-v7a. On 2026-11-25 `cloudflared-linux-arm` 2026.11.2 ran on it from adb. It needed
  `SSL_CERT_DIR=/system/etc/security/cacerts`: Go does not know the Android CA folder. DNS, QUIC and HTTP/2 to the
  Cloudflare edge passed.
- The test tunnel: `telik-tv` in the owner's Cloudflare account, `tv.slim-shaggy.com` to `http://localhost:8766`. Its
  token is the secret `TELIK_CF_TUNNEL_TOKEN`.

## Decisions

### D1. cloudflared in the APK

- A Gradle task downloads `cloudflared-linux-arm` of a pinned version, checks its SHA-256, and puts it into a
  generated `jniLibs/armeabi-v7a/libcloudflared.so`. The build fails on a wrong checksum (constitution IV).
- `packaging.jniLibs.useLegacyPackaging = true`, so Android extracts the file to `nativeLibraryDir`, where an app can
  run it. The strip step skips this file (`keepDebugSymbols`).
- `cloudflared` is Apache-2.0: `THIRD_PARTY_NOTICES.md` gets a line.

### D2. Run the tunnel

`CloudTunnel` starts `libcloudflared.so tunnel --no-autoupdate run` with `ProcessBuilder`:

- The token goes in the environment (`TUNNEL_TOKEN`), not in the arguments, so the process list does not show it.
- Telik resolves `region1` and `region2.v2.argotunnel.com` and gives the addresses with `--edge`. In the app sandbox
  the Go resolver of cloudflared cannot read the vendor `resolv.conf` (tasks T004).
- `HOME` is a private folder. `SSL_CERT_DIR` is the Android CA folder.
- A reader thread keeps the last 40 lines of the output for the settings. It finds "Registered tunnel connection" for
  the state, and the hostname in the "Updated to new configuration" line.
- If the process stops while the setting is on, Telik starts it again after 5, 10, 20 … 300 seconds.
- `AirPlayService` starts and stops it with `Prefs.CLOUD_ENABLED`, `Prefs.MCP_ENABLED` and the token.

### D3. Remote requests

cloudflared connects from `127.0.0.1` and adds `Cf-Connecting-Ip`. A request with this header is remote:

- `/mcp` needs `Authorization: Bearer` with an OAuth access token or the token of spec 011. Without it, the answer is
  401 with `WWW-Authenticate: Bearer resource_metadata="https://<host>/.well-known/oauth-protected-resource/mcp"`.
- The `Origin` rule of spec 011 stays for `/mcp`. The OAuth pages accept the `Origin` of the public host.
- The public host comes from the `Host` header of the request, and must equal the hostname that cloudflared reported.

### D4. OAuth 2.1 server in Telik

`OAuthServer` is pure Kotlin with a clock and a random source, so it has unit tests. `OAuthStore` keeps its state in
private preferences as JSON.

| Path | Use |
|------|-----|
| `GET /.well-known/oauth-protected-resource[/mcp]` | `resource`, `authorization_servers`, `bearer_methods_supported` |
| `GET /.well-known/oauth-authorization-server` | issuer, endpoints, `S256`, `none` client auth, grant types |
| `POST /register` | Dynamic client registration. Redirect addresses must pass FR-005. At most 20 clients |
| `GET /authorize` | Checks the request, starts a TV confirmation, gives an HTML page that waits for it |
| `GET /authorize/wait?id=` | The page asks each second. It gets the redirect address after OK |
| `POST /token` | `authorization_code` with PKCE, and `refresh_token` with rotation |

- Codes live 60 seconds and work once. Access tokens live 1 hour. Refresh tokens live 90 days and rotate.
- Telik keeps only SHA-256 hashes of tokens.
- At most 3 confirmations wait at a time. A confirmation ends after 2 minutes.

### D5. Confirmation on the TV

An overlay window shows the client name, the code of the page, "Allow" and "Deny". Back denies. An activity does not
work here: Android 14 starts the activity of a background app behind the current app (tasks T013).

### D6. Token from the phone

The settings show a QR code of `http://<tv-ip>:8766/setup?code=<6 digits>`. The code is valid 10 minutes, for one
token. The page has one text field. A POST with the correct code stores the token and starts the tunnel. adb can
also send `SET_CLOUD_TOKEN` to `SettingsReceiver`.

## Code

- `app/build.gradle.kts`: the download task, the jniLibs folder, the packaging.
- `cloud/CloudTunnel.kt`, `cloud/CloudToken.kt` (token and setup code).
- `mcp/OAuthServer.kt` (pure, with the `OAuthStore` interface), `mcp/WebRoutes.kt` (metadata, OAuth, setup and authorize pages).
- `McpServer`: routes for OAuth and setup, remote detection. `McpProtocol.route` gets the access check from outside.
- `mcp/AgentApprove.kt` (the overlay), settings items, strings in three languages.
- The `power` tool in `TvTools`: status, standby with the lock screen action, on with a wake lock.
- Docs: `docs/MCP.md` section "claude.ai and the phone". Changelog.

## Check on the TV

Install the dev build. Set the token with adb from the secret store. Check the state in the settings. Add the
connector in claude.ai with `https://tv.slim-shaggy.com/mcp`. Confirm on the TV. Ask Claude on the phone for the media
state and a pause.
