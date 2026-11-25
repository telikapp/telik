# Tasks: The TV as a claude.ai connector

## Phase 1: cloudflared on the TV (US1)

- [x] T001 Check that `cloudflared-linux-arm` runs on the test TV. Find what it needs.
  - 2026-11-25: 2026.11.2 ran from adb. It needs `SSL_CERT_DIR=/system/etc/security/cacerts`.
- [x] T002 Create the test tunnel `telik-tv` and `tv.slim-shaggy.com` in the owner's Cloudflare account. Keep the token in the secret store.
- [x] T003 Add the Gradle task that downloads and checks `cloudflared`, the jniLibs folder and the packaging. Add the license line.
  - The APK grew from 11.7 MB to 26.8 MB. `libcloudflared.so` is 18.4 MB compressed.
- [x] T004 Add `CloudTunnel`: start, output, state, hostname, restart with a delay. Unit tests for the output parse.
  - 2026-11-25: in the app sandbox, the Go resolver of cloudflared failed. The vendor `resolv.conf` is closed to apps (SELinux), so it asked `[::1]:53`. Telik now resolves `region1` and `region2.v2.argotunnel.com` with the system resolver and gives the addresses with `--edge`.
- [x] T005 Add `Prefs.CLOUD_ENABLED`, `CloudToken`, `SET_CLOUD_TOKEN`, and the start and stop in `AirPlayService`.

## Phase 2: OAuth (US2)

- [x] T010 Add `OAuthServer`: metadata, registration, authorization, PKCE, codes, tokens, refresh rotation. Unit tests.
- [x] T011 Keep the OAuth state with token hashes in private storage.
- [x] T012 Add the routes, the remote detection and the 401 with `resource_metadata` to `McpServer`. Unit tests for the route rules.
- [x] T013 Add the authorize page and the confirmation on the TV.
  - 2026-11-25: an activity started behind the current app (Android 14 `TASK_LAUNCHING_BEHIND`) and closed at once. The confirmation is now an overlay window.

## Phase 3: Settings and setup page (US1, US3)

- [x] T020 Add the settings: the switch, the state, the address, the QR code, "Disconnect all agents". Strings in three languages.
- [x] T021 Add the setup page with the one-time code. Unit tests.
- [ ] T022 Check the settings screen and the QR setup page on the TV with a phone. The check of 2026-11-25 set the token with adb.

## Phase 4: Check and release

- [x] T030 Check on the TV: the tunnel state, the metadata from the internet, a request without a token.
  - 2026-11-25: `/.well-known/oauth-protected-resource/mcp` answered from the internet. `/mcp` without a token gave 401 with `resource_metadata`. `/setup` through the tunnel gave 404.
  - A Python client got 403 from Cloudflare bot protection (`Python-urllib` user agent). curl and a named user agent passed.
- [x] T031 Connect claude.ai, confirm on the TV, use it from the Claude mobile app.
  - 2026-11-25: a script did the full flow of claude.ai through the tunnel: registration, the TV confirmation with OK, the code, the token, a second use of the code (400), `initialize`, `media`, refresh rotation.
  - 2026-11-25: the owner added the connector in claude.ai, confirmed on the TV and said that all works.
- [x] T033 Add the `power` tool: status, standby, on. The owner asked for it on 2026-11-25.
  - 2026-11-25: off gave standby. After 20 s in standby the tunnel still answered. On woke the TV in 601 ms.
- [ ] T034 Check how long the tunnel stays up in standby (hours, a night). It depends on the network policy of the TV.
- [ ] T032 Docs, README, changelog. Pull request stacked on spec 011.
