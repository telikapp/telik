# Tasks: Agent control of the TV (MCP)

## Phase 1: Server and security (US4, P1)

- [x] T001 [US4] Add `McpHttp`: request parse with a size limit, the local address check, the response. Unit tests.
- [x] T002 [US4] Add `McpProtocol`: JSON-RPC, `initialize` with the version choice, `tools/list`, `tools/call`, the token and `Origin` checks in `route`. Unit tests.
- [x] T003 [US4] Add `McpServer` and `McpToken`. Start and stop it in `AirPlayService` with `Prefs.MCP_ENABLED` (default off).
- [x] T004 [US4] Add `SET_MCP_TOKEN` to `SettingsReceiver` (callers with `DUMP` only).
- [x] T005 [US4] Add the setting, the token, the `claude mcp add` command and "New token" to `TvScreens` and `SettingsScreen`. Strings in English, Russian and Chinese (Taiwan).
- [x] T006 [US4] Check on the TV: no token, a wrong token and an `Origin` header fail with 401, 401 and 403. `GET` gets 405.
  - 2026-11-24: checked with curl against `dev.telik.tv.dev` on the test TV.

## Phase 2: Screen and actions (US1, US2, US3, P1)

- [x] T010 [US1] Add `UiTree`: the node filter, the line format, the search, the cut note. Unit tests.
- [x] T011 [US3] Add `TvEvents`: the version, the window state, the waits. Unit tests.
- [x] T012 [US1] Send the accessibility events to `TvEvents`. Turn on view ids at run time.
- [x] T013 [US1, US2, US3] Add the tools `screen`, `find`, `act`, `key`, `tap`, `type`, `wait`, `apps`, `launch` in `TvTools`.
- [x] T014 [US1] Remove the `screenshot` tool and `canTakeScreenshot` (owner decision, 2026-11-24). A first version had it. Its first picture after the service start was broken.
- [x] T015 [US1] Drop the 10x10 px placeholders of YouTube (Cobalt) and say "no text in the tree" with the structured paths. Remove U+2060 and other invisible characters from text and search (KinoPub). Unit tests.
- [x] T016 [US1, US2, US3] Check on the TV with Claude Code: launch YouTube, key, key ok, media pause, key back, key home.
  - 2026-11-24: passed with the screenshot tool. YouTube gave no tree, so the agent walked the menu with pictures. The key tools said "no screen change": Cobalt sends no accessibility events.
- [x] T017 Say "no accessibility event" instead of "no screen change". Wait 800 ms after a key, not 2 s.
- [x] T018 Check T015, T017 and the media fallback (T024) on the TV after the user's movie.
  - 2026-11-24 22:15: T015 passed. YouTube gave `nodes=1` and the note. T017 passed: "no accessibility event" in YouTube.
  - In KinoPub, events came but the screen did not get quiet, and the message said "no accessibility event". Now it says "screen still changing".
  - 2026-11-25: the media fallback passed in the KinoPub player: pause, play, pause. The seek of 30 s gave the error "the app did not seek".
  - `key home` gave the old app: the launcher came after the wait of 800 ms. Home now waits 2 s.
- [ ] T019 `tap` and the tap fallback of `act`: the test TV cancelled the gesture (it has no touch screen). On such TVs, use node actions and keys.

## Phase 3: Structured Android data (D6)

- [x] T020 Add `AxmlParser` and `ManifestSummary`: the binary manifest, the filters, the deep links. Unit tests with a test writer.
- [x] T021 Add `app_info`, `open`, `search`, `media`, `tv_rows`, `debug`, `recipe`.
- [x] T022 Check the read tools on the TV with Claude Code: `screen`, `media`, `apps`, `app_info`, `tv_rows`.
  - 2026-11-24: `claude -p` with `--mcp-config`, the token from the secret store. Output in `research.md`.
- [x] T023 Check the action tools on the TV: `open` with deep links, `search`, `media` pause, play and skip.
  - 2026-11-24: results in `research.md`. KinoPub took no `pause` and no `seek` from the transport controls. `search` into KinoPub failed with a start error.
- [x] T024 `media`: check the state after play and pause. If it did not change, send the media key to the session, then the play/pause key of the remote. Check the position after a seek and say when the app ignores it.
- [x] T025 Give the class and the message of the exception when an activity start fails.
- [x] T026 Find why `search` into KinoPub `InfoActivity` fails (T025 shows the cause).
  - 2026-11-24: `ActivityNotFoundException`. The SEARCH filter has no `CATEGORY_DEFAULT`. Telik now starts the resolved activity with an explicit intent.
  - KinoPub then starts, but it shows no results. Use its search button.
- [x] T027 Check `debug` on the TV. It needs one approval of the adb key on the TV screen.
  - 2026-11-25: `activity`, `media` and `logcat` of `dev.telik.tv.dev` gave the data. A package name with a command in it was refused.

## Phase 4: The three apps (research.md)

- [x] T030 Read the manifests of YouTube, KinoPub and Apple TV: version, activities, filters, deep links, providers.
- [x] T031 Read MediaSession, Watch Next and the preview channels of the three apps.
- [x] T032 Check the deep links of each app on the TV, the UI tree of the main screens, and the search.
- [x] T033 Write the tested steps of each app into `Recipes.kt` and `research.md`.

## Phase 5: Release

- [x] T040 Add `docs/MCP.md`, a README line, and the changelog `23.txt` in English and Russian.
- [ ] T041 Open the stacked pull requests. CI must pass.
