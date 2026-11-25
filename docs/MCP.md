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
| `apps`, `app_info`, `launch`, `open`, `search`, `recipe` | Nothing more |
