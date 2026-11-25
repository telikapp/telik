# Agent control (MCP)

Telik has an MCP server. An AI agent on a computer in the same network uses it. Examples of agents: Claude Code
and other MCP clients. The agent reads the TV screen, opens apps and presses keys. The server is in Telik. You install nothing on the TV, and the agent does
not need adb.

## Turn it on

1. Turn on accessibility access for Telik, as for the iPhone remote.
2. For the `media` tool, allow notification access for Telik (Settings > Apps > Special app access).
3. In Telik, open **Settings > Remote** and turn on **Control by AI agents**. The server is off by default.
4. Telik shows the token and the command for the computer, for example:

   ```sh
   claude mcp add --transport http telik http://192.168.0.13:8766/mcp --header "Authorization: Bearer <token>"
   ```

5. **New token** makes a new token. The old token then stops working.

Other MCP clients use the same address with the transport "Streamable HTTP" and the header
`Authorization: Bearer <token>`.

### Keep the token out of files

With a secret store, write a reference in `.mcp.json`. Claude Code reads `${VAR}` from the environment:

```json
{"mcpServers": {"telik": {"type": "http", "url": "http://192.168.0.13:8766/mcp",
  "headers": {"Authorization": "Bearer ${TELIK_MCP_TOKEN}"}}}}
```

A developer with adb can set the token from the computer, so that nobody types it:

```sh
adb shell am broadcast -n dev.telik.tv/io.github.henry753951.airplayremote.SettingsReceiver \
  -a io.github.henry753951.airplayremote.SET_MCP_TOKEN --es token "$TELIK_MCP_TOKEN"
adb shell am broadcast -n dev.telik.tv/io.github.henry753951.airplayremote.SettingsReceiver \
  -a io.github.henry753951.airplayremote.SET_SETTING --es key mcp_enabled --es value true
```

The token has 20 to 128 characters: letters, digits, `_` and `-`. Only adb can send these broadcasts.

## Claude on the phone and on the web (claude.ai)

claude.ai connects to a connector from the Anthropic cloud, not from your phone. So the TV needs a public HTTPS
address. Telik runs a Cloudflare Tunnel for it. Telik includes `cloudflared`, so you install nothing on the TV.

You need a Cloudflare account and a domain on Cloudflare.

1. In the Cloudflare dashboard, open **Networking > Tunnels** and create a tunnel, for example `telik-tv`.
2. On the **Routes** tab of the tunnel, add a published application: a hostname such as `tv.example.com`, service
   `http://localhost:8766`.
3. Copy the token of the tunnel. It is in the install command, after `--token`.
4. In Telik, turn on **Control by AI agents**, then **Access from claude.ai**.
5. Select **Tunnel token**. Scan the QR code with a phone in the same network, paste the token and select **Save**.
   The code of the QR page works once, for 10 minutes.
6. **Tunnel** shows "Connected" and the address `https://tv.example.com/mcp`.
7. In claude.ai, open **Settings > Connectors > Add custom connector** and enter that address. Select **Connect**.
8. The page shows a code, and the TV asks "Claude wants to control this TV" with the same code. Press OK.
9. The connector also works in the Claude app on the phone.

**Disconnect all agents** removes the access of all OAuth clients. They must connect and get your OK again.

A developer with adb can set the tunnel token from the computer:

```sh
adb shell am broadcast -n dev.telik.tv/io.github.henry753951.airplayremote.SettingsReceiver \
  -a io.github.henry753951.airplayremote.SET_CLOUD_TOKEN --es token "$TELIK_CF_TUNNEL_TOKEN"
```

How it works:

- Requests through the tunnel need an OAuth access token (OAuth 2.1, PKCE S256, dynamic client registration) or the
  agent token. Without a token, `/mcp` answers 401 with the address of the OAuth metadata.
- The redirect addresses must be the Claude callback or a loopback address. Each new client needs an OK on the TV.
- Access tokens live 1 hour, refresh tokens 90 days. Telik keeps only their SHA-256 hashes.
- The setup page is open only on the local network, not through the tunnel.
- Cloudflare bot protection can block some clients by their User-Agent. If claude.ai cannot connect, check the
  security events of the domain in Cloudflare.

## Security

- Off by default. The port 8766 is open only while the setting is on.
- Each request needs the token. Telik compares it in constant time.
- Telik accepts only clients from the local network: private, link-local and loopback addresses.
- Telik refuses requests with an `Origin` header, so a web page cannot control the TV.
- The log has only the method and the tool name. The token, the typed text and the screen text stay out of it.

## Tools

The tools give structured data only. Telik gives no screenshots. Some apps draw their own UI and give no
accessibility tree, for example YouTube and Apple TV. For them, the agent uses deep links, the media session, TV
Provider rows and keys. The `recipe` tool gives the tested steps.

| Tool | Use |
|------|-----|
| `screen` | The current app and activity, and the compact UI tree: `#id role "text" id=viewId [l,t,r,b] flags` |
| `find` | Nodes by text, description or view id |
| `act` | A node action by id: click, focus, scroll, set text, clear |
| `key` | Remote keys: D-pad, OK, Back, Home, media, volume |
| `tap` | Tap or swipe at coordinates |
| `type` | Text into the open text field, with submit |
| `wait` | Wait for a screen change, a new window, or a text |
| `apps`, `app_info` | Apps, versions, deep links, search support, permissions |
| `launch`, `open`, `search` | Open an app, a deep link, an `intent:` URI, or a search |
| `media` | Now playing, and play, pause, seek, skip, next |
| `tv_rows` | Watch Next, preview programs and channels of the TV Provider |
| `recipe` | Tested steps for YouTube, KinoPub and Apple TV |
| `power` | Status, standby (off) and on |
| `debug` | `dumpsys` and `logcat` over the adb port of the TV. Needs network debugging |

The action tools wait until the screen is quiet (300 ms, at most 2 s). Their answer has the screen version `v`
and the current app. The node ids are valid until the next `screen` or `find`.

## What each tool needs

| Tool | Needs |
|------|-------|
| `screen`, `find`, `act`, `key`, `tap`, `type`, `wait` | Telik accessibility access |
| `media` | Telik notification access |
| `tv_rows` | The TV listings permission. Apps show only rows that a launcher can read |
| `debug` | Network debugging on the TV and one approval of the Telik adb key |
| `power` | Telik accessibility access for standby |
| `apps`, `app_info`, `launch`, `open`, `search`, `recipe` | Nothing more |
