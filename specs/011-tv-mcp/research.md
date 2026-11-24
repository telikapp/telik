# Research: YouTube, KinoPub and Apple TV on the test TV

**Date**: 2026-11-24. **TV**: Dahua 4K, Android 14, at 192.168.0.16 on that day (192.168.0.13 before).
**Method**: `dumpsys package` over adb gave the manifests. The Telik MCP tools (`app_info`, `media`, `tv_rows`,
`screen`, `open`, `search`, and a `screenshot` tool of the first version) in Claude Code gave the rest. The raw output is in
`/Volumes/SanDisk2Tb/builds/telik/mcp-check/`.

<!-- STE note: the tables keep the Android names of actions and components. -->

## The three apps in one table

| | YouTube | KinoPub (kino.watch) | Apple TV |
|---|---|---|---|
| Package, version | `com.google.android.youtube.tv` 7.32.303 | `watch.kino` 2.26 | `com.apple.atve.androidtv.appletv` 17.0.4 |
| Launch activity | `.activity.ShellActivity` (one activity for all) | `.activity.LaunchActivity` | `.MainActivity` |
| Deep links (manifest) | `http(s)://{youtube.com, www., m.}youtube.com/*`, `youtu.be/*`, `vnd.youtube:`, `vnd.youtube.launch:`; Cast `LAUNCH` for `https://www.youtube.com/*` | `http(s)://*/item/view*` (any host) on `InfoActivity`; `tvrecommendation://app/browse/*` and `/playback/*` | `https://tv.apple.com` (any path) |
| Search intents | `MEDIA_PLAY_FROM_SEARCH` only with a `vnd.youtube`/https URI; no `SEARCH` | `SEARCH` on `InfoActivity` (searchable) | none |
| Exported providers | none | `.data.VideoProvider` (authority `watch.kino`) | none (only `androidx.startup`) |
| MediaSession | yes: title, channel, position, length; play, pause, seek, next, previous, ff, rew. Title comes 1-2 s after the start | state, position, length; no title. It ignores `pause` and `seekTo` of the transport controls; the play/pause key works | a `STOPPED` session with actions while nothing plays; playback not checked |
| TV Provider, Watch Next | yes, with `https://www.youtube.com/watch?v=…` links | yes, `tvrecommendation://app/playback/0/<id>` | no rows (Apple uses the Engage SDK `PUBLISH_CONTINUATION`) |
| TV Provider, preview channels | "Рекомендовано вам", "В тренде", "YouTube Music" (not browsable for the launcher, but the programs can be read) with `intent://www.youtube.com/watch?v=…` | "Новые сериалы", "Новые фильмы", "Я смотрю", 55 programs | one browsable channel, link `https://tv.apple.com/Watch-Now`, 0 programs readable |
| UI tree in the player | none: a root and a 3x3 grid of 10x10 px placeholders (Cobalt) | 2 nodes: `root`, `player_view` ("Показать панель управления") | none |
| UI tree in the browse screens | none, same placeholders; no accessibility events on focus moves | good: 20-60 nodes, view ids (`btnWatch`, `tvTitle`, `action_search`), focus, text. Titles have U+2060 inside words | none: 0 nodes, no events |
| Deep links on the TV | `results?search_query=`, `@handle`, `watch?v=` open the right screen in 5-10 s | `kino.pub/item/view/<id>` opens the title page | a title link opens the title page; `search?term=` opens an empty search |
| Search | the `results` link | the search button `action_search`, then type and submit; the `SEARCH` intent failed to start | not by link |
| Own API | YouTube Data API v3 (official, needs an API key) for search; DIAL/Cast for the remote control | community API of kino.pub (`api.service-kp.com`, OAuth device flow) | none public |

## Notes

### Manifests

- YouTube puts all filters on `ShellActivity`. `MEDIA_PLAY_FROM_SEARCH` is in the same filter as the data schemes,
  so it matches only an intent with a URI. The `search` tool with `play=true` cannot reach YouTube. Use a
  deep link.
- KinoPub accepts `/item/view/<id>` on any host, so `https://kino.pub/item/view/<id>` opens the title. The host does
  not matter. `tvrecommendation://app/playback/0/<id>` from Watch Next starts the player at the saved position.
- Apple TV accepts all `https://tv.apple.com` links on `MainActivity`. Domain verification is on.

### MediaSession

The `media` tool needs the notification access of Telik. YouTube gave the full metadata and the position:
`playing "…" by "А поговорить?" pos=2:19:53 len=2:28:48 can=play,pause,seek,next,previous,ff,rew`.
Apple TV keeps a session in state `STOPPED` with actions but no metadata while nothing plays.

### UI tree

- YouTube is Cobalt, a web engine. In the menus and in the player, the tree has a root `FrameLayout` and nine 10x10 px
  `View` placeholders for the focus of screen readers. Cobalt also sends no accessibility event when the focus moves.
  `uiautomator dump` gives the same 14 nodes, so no accessibility client gets more.
- Apple TV gives 0 nodes and no events.
- KinoPub is a normal Android app with view ids. Only the player has a single `player_view`.
- The first version had a `screenshot` tool. With it, the agent walked the YouTube menu: right, then OK, from the
  picture. The owner removed the tool on 2026-11-24 (plan D4). For YouTube and Apple TV the agent now uses links,
  TV Provider rows, the media session and the keys (see `recipe`).

### Own APIs (proposal, not in this feature)

- **YouTube Data API v3**: `search.list` gives video ids for a query. A tool `youtube_search(query)` on the agent
  side, with the user's API key, could give ids for `open uri=https://www.youtube.com/watch?v=<id>`. It is not in
  Telik: constitution III says that Telik asks for no account, and an API key is an account.
- **YouTube DIAL and Cast**: the YouTube app is a Cast receiver (`cast.tv.action.LAUNCH`). An agent can use the Cast
  protocol from the computer. In Telik, `open` with a deep link does the same on the TV.
- **kino.pub API**: the community clients use `api.service-kp.com/v1` with an OAuth device code and the user's
  subscription. A tool `kinopub_search(query)` on the agent side could give item ids for `open`. It is an
  unofficial API, so it is a proposal only.
- **Apple TV**: no public API. Use deep links to `tv.apple.com`.
