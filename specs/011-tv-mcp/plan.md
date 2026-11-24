# Plan: Agent control of the TV (MCP)

**Spec**: `spec.md` | **Branch**: `andrewkomkov/tv-mcp`

## Technical context

- Kotlin 2, minSdk 33, targetSdk 37. The test TV: Dahua 4K, Android 14 (API 34), armeabi-v7a, screen 1920x1080.
- Telik already has: the accessibility service `RemoteAccessibilityService` (keys, gestures, an input method for
  text), the Shortcuts HTTP server `ShortcutServer` (off by default, bearer token), the notification listener
  `NowPlayingListener` (a key for `MediaSessionManager`), `READ_TV_LISTINGS` (TV Provider rows), and `LocalShell`
  (adb client to the adb port of the same TV, for the Headphones button).
- No new library. The server is a small HTTP/1.1 server on a `ServerSocket`, as `ShortcutServer`, and JSON is
  `org.json` of Android.
- Local check: `devbuild.sh` installs `dev.telik.tv.dev` next to the real Telik.

## Decisions

### D1. The server is in Telik, not on the computer

The MCP server runs in Telik, in `AirPlayService`, which runs in the background. The accessibility service gives the
screen and the actions without adb.

- Constitution I: the user installs nothing else. The agent is the only program on the computer, and it connects
  with its normal MCP client.
- A server on the computer over adb needs adb, a paired computer and an extra program. It also loses the input method
  of Telik for text. It is not in this feature. The `debug` tool (D6) gives the adb data that helps for debug, from
  the TV itself.

### D2. Transport: MCP Streamable HTTP, JSON answers

- `POST /mcp` takes one JSON-RPC message. A request gets `application/json` with the answer. A notification or a
  response gets `202 Accepted`. `GET` and `DELETE` get `405`: the server sends no messages of its own, so it needs no
  SSE stream and no session (both are optional in the MCP specification).
- Protocol versions: `2025-11-25`, `2025-06-18`, `2025-03-26`. The server answers with the version of the client if
  it knows it, else with the newest.
- Methods: `initialize`, `ping`, `tools/list`, `tools/call`, and the notifications. Other methods get `-32601`.
- Port 8766 (the Shortcuts API has 8765). Up to 4 requests at a time. The actions go one at a time, in order.

### D3. Security (spec US4, FR-009 to FR-011, FR-013, FR-014)

- `Prefs.MCP_ENABLED`, default off. The server listens only while it is on.
- A token of 128 random bits (`ShortcutApi.newToken`) in its own private file `mcp`, apart from the Shortcuts token
  and apart from the settings that the adb receiver lists. `Authorization: Bearer <token>`, compared in constant time.
- The client address must be loopback, link-local or private (IPv4 10/8, 172.16/12, 192.168/16, IPv6 fc00::/7).
- A request with an `Origin` header fails with 403: a web page in a browser cannot use the TV (DNS rebinding).
- The log has the method, the tool name and the status. It never has the token, the typed text or screen text.
- The settings show the address, the token and a ready `claude mcp add` command, and a "New token" button.
- For a developer with adb: `SettingsReceiver` (only for callers with `DUMP`, that is adb shell) gets the action
  `SET_MCP_TOKEN`. The token then goes from a secret store on the computer to the TV and is not shown.

### D4. Structured data only, no screenshots

The agent works with structured Android data. A first version had a `screenshot` tool for apps without a tree. The
TV check showed that YouTube (Cobalt) and Apple TV give no tree at all, so the agent used pictures for them. The owner
decided on 2026-11-24 to remove the tool. For these apps the agent uses deep links, TV Provider rows, the media
session and the keys, and the `recipe` tool gives the tested steps. The tool descriptions are one short line each.

### D5. The UI tree

- The tree comes from `getWindows()` (the service has `flagRetrieveInteractiveWindows`). The service turns on
  `FLAG_REPORT_VIEW_IDS` at run time while the server is on, so the remote does not change.
- Compact form: only nodes that are visible to the user, and that have text or a description, or that can be
  clicked, focused, scrolled, checked or edited. One line for each node:
  `#12 Button "Search" id=search_button [100,200,300,260] focused clickable`.
  The class name has no `android.widget.` prefix. Ids that start with the package lose the package prefix.
- The ids are the index of the node in the last answer. Telik keeps the nodes of the last answer. An action on an
  old id calls `refresh()`; if the node is gone, the answer is "stale: call screen again".
- `find` searches the nodes of a new snapshot by text, description or id (no case, part of the text).
- `act` does a node action without coordinates: `click`, `long_click`, `focus`, `select`, `scroll_forward`,
  `scroll_backward`, `scroll_up/down/left/right` (API 23 actions), `set_text`, `clear`. A click on a node that cannot
  be clicked goes to the nearest parent that can. A scroll without a node goes to the first node that scrolls.
- The current activity: the class of the last `TYPE_WINDOW_STATE_CHANGED` event, if `PackageManager` knows it as an
  activity of that package.

### D6. The tools

Each tool says what it needs. "Launcher" means: works in Telik as a normal app, without root.

| Tool | What it does | Needs | Launcher, no root |
|------|--------------|-------|-------------------|
| `screen` | Current app and activity, compact UI tree, screen version | Accessibility service of Telik | Yes |
| `find` | Nodes by text, description or view id | Accessibility service | Yes |
| `act` | Node action: click, focus, scroll, set text, clear | Accessibility service | Yes |
| `key` | D-pad, OK, Back, Home, media and volume keys | Accessibility service (global actions, API 33 D-pad) | Yes |
| `tap` | Tap or swipe at coordinates (fallback) | Accessibility service, `canPerformGestures` | Yes |
| `type` | Text into the focused field, optional submit | Accessibility service input method (API 33) | Yes |
| `wait` | Wait for a screen change, a window change, or a text | Accessibility events | Yes |
| `apps` | Launchable apps: package, name, version | `<queries>` for LAUNCHER and LEANBACK_LAUNCHER | Yes |
| `app_info` | Version, activities, permissions, deep links (schemes, hosts, paths), SEARCH and ASSIST support | `PackageManager`, and the binary manifest of the APK (`sourceDir` can be read by all apps) | Yes, for the apps that `<queries>` makes visible |
| `launch` | Open an app by package | Accessibility service as the start context (Android 14 blocks background starts) | Yes |
| `open` | Start an activity from an intent: `ACTION_VIEW` with a URI (deep link), `ACTION_SEARCH`, `ACTION_ASSIST`, an `intent:` URI of TV Provider | Same as `launch`. Activities only, no broadcasts or services | Yes |
| `media` | Now playing: app, title, state, position, length, actions. Control: play, pause, toggle, next, previous, stop, seek | Notification listener access of Telik (the user allows it once) | Yes |
| `tv_rows` | TV Provider: channels, preview programs and Watch Next of all apps, with the intent URI | `READ_TV_LISTINGS` (runtime permission on the TV) | Yes: only the rows that apps mark as browsable or searchable. `ACCESS_ALL_EPG_DATA` is a system permission |
| `search` | Search in an app with `ACTION_SEARCH` and the query, or list the apps that accept it | `PackageManager` | Yes. The global search providers need `GLOBAL_SEARCH`, a system permission, so Telik does not query them |
| `debug` | `dumpsys activity`, `window`, `media_session`, `logcat` of one app | Network debugging on the TV and one approval of the Telik adb key (as the Headphones button) | Yes, with adb on the TV. Fixed commands only, no free shell |

Each action tool (`act`, `key`, `tap`, `type`, `launch`, `open`, `media`) waits until the screen is quiet for 300 ms,
or 2 s at most, and gives back the new screen version and the current app. Then the agent calls `screen` or `wait`.

### D7. Events and wait

The accessibility service sends each event to `TvEvents`: a version number that grows on each event, the time of the
last event, and the last window change (package, class). `wait` has these modes:

- `change`: the version grows above `since` (default: the version at the call), then the screen is quiet for 300 ms.
- `window`: a new window state event, optional with a package.
- `text`: a node with the text shows. The check runs on each event, at most each 200 ms.

The time limit is 5 s by default and 30 s at most. The answer has the mode result, the time, the version and the app.

### D8. Deep links from the manifest

`PackageManager` does not give the intent filters of other apps. `app_info` reads `AndroidManifest.xml` from the
APK (`ApplicationInfo.sourceDir`) with a small parser of the binary XML format (`AxmlParser`, pure Kotlin, unit
tests with a test writer). It keeps the activity filters with the action `VIEW`: schemes, hosts, path prefixes. It
also says which activities accept `SEARCH` and `ASSIST`. If the read fails, the tool gives the rest without links.

## Code

- `mcp/McpHttp.kt`: pure request parse (request line, headers, body by `Content-Length`, size limit), the address
  check, the response bytes. Unit tests.
- `mcp/McpProtocol.kt`: pure JSON-RPC and MCP: version, errors, `tools/list`, `tools/call` with the tool table. Unit
  tests with fake tools.
- `mcp/UiTree.kt`: pure node data, filter and line format, and the text search. Unit tests.
- `mcp/AxmlParser.kt`: the binary manifest parser and the deep-link pick. Unit tests.
- `mcp/TvEvents.kt`: the event state and the wait logic, with a clock for tests.
- `mcp/TvTools.kt`: the Android side of the tools.
- `mcp/McpServer.kt`: the socket server, as `ShortcutServer`. `McpToken` in the same file.
- `RemoteAccessibilityService`: events to `TvEvents`, keys without the menu binding, text for the agent.
- `AirPlayService`: start and stop with `Prefs.MCP_ENABLED`. `SettingsReceiver`: `SET_MCP_TOKEN`.
- `TvScreens.kt` and `SettingsScreen.kt`: the setting, the address, the token, the command, "New token". Strings in
  English, Russian and Chinese (Taiwan).
- `docs/MCP.md`: how to connect an agent. README and changelog `23.txt` in English and Russian.

## Check on the TV (constitution V, VI)

Install with `devbuild.sh` only when no media plays. Turn on accessibility access for `dev.telik.tv.dev` next to the
real Telik. Set the token from the secret store with adb (`SET_MCP_TOKEN`). Connect Claude Code with
`claude mcp add --transport http`. Run: `apps`, `launch` YouTube, `wait`, `screen`, `find`, `act` click, `key back`,
`key home`. Keep the logs in `/Volumes/SanDisk2Tb/builds/telik/mcp-check/`.

## Pull requests

A stack of two, each with green CI:

1. `feat(mcp)`: spec 011, server, protocol, token, settings, and the screen, find, act, key, tap, type, wait, apps,
   launch tools.
2. `feat(mcp)`: the structured data tools (`app_info`, `open`, `search`, `media`, `tv_rows`, `recipe`, `debug`). Also
   the docs, the README and the changelog.
