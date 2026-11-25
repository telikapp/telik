# Research: claude.ai connectors and Cloudflare Tunnel on the TV

**Date**: 2026-11-25.

## claude.ai custom connectors

Sources: https://claude.com/docs/connectors/custom/add-unlisted, https://claude.com/docs/connectors/building/authentication.md,
https://support.claude.com/en/articles/11176164-use-connectors-to-extend-claude-s-capabilities

- Claude connects from the Anthropic cloud for all clients, also for the mobile app. Ranges: `160.79.104.0/21`,
  `153.61.208.0/20`.
- HTTPS on port 443 only. The full URL with the path, for example `https://tv.example.com/mcp`.
- Streamable HTTP. The MCP transport lets a server answer a POST with `application/json`. The Anthropic docs do not
  say more. Telik checks it on the TV (T031).
- Authentication: none, OAuth with dynamic client registration ("Register automatically"), OAuth with Claude's
  client metadata document, or an own client id. Static headers are a beta for some organizations only.
- OAuth rules: 401 with `WWW-Authenticate: Bearer resource_metadata="…"`; `resource` equal to the URL of the user;
  RFC 8414 metadata; PKCE `S256`; form body at the token endpoint; `invalid_grant` for a dead refresh token; refresh
  rotation for public clients; 10 s limit for discovery, registration and token.
- Callback: `https://claude.ai/api/mcp/auth_callback` for web, Desktop and mobile.
- A connector added on the web shows in the mobile app at the next login. Free plans allow one custom connector.
- MCP tunnels of Anthropic: Enterprise only (https://claude.com/docs/connectors/mcp-tunnels/overview).

## Cloudflare Tunnel

Sources: https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/,
https://developers.cloudflare.com/tunnel/reference/run-parameters/, https://github.com/cloudflare/cloudflared/releases

- Quick tunnels need no account, but the hostname changes at each start, there is no uptime promise, and Cloudflare
  calls them a test feature. The owner chose a tunnel with an own domain.
- A remotely-managed tunnel runs with a token only (`TUNNEL_TOKEN`). The public hostname needs a domain on Cloudflare.
- `cloudflared-linux-arm` 2026.11.2: 37 148 657 bytes, static Go, SHA-256
  `d219494581180764c6d2454409691bb06879d01b43cdeae12476070a7e4e4762` (same as the GitHub asset digest).
- Cloudflare gives no library for an app (issue #1402, closed as not planned).

## Check on the test TV

- 2026-11-25: the binary ran from adb as the shell user. Without `SSL_CERT_DIR` the TLS check failed
  (`x509: certificate signed by unknown authority`). With `SSL_CERT_DIR=/system/etc/security/cacerts` a quick tunnel
  registered (QUIC, `vie06`). DNS, UDP and TCP pre-checks passed.
