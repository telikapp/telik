# Feature Specification: The TV as a claude.ai connector (Cloudflare Tunnel)

**Feature Branch**: `andrewkomkov/tv-cloud` (stacked on `andrewkomkov/tv-mcp-data`, spec 011)

**Created**: 2026-11-25

**Status**: Draft

**Input**: The owner said on 2026-11-25: "I want it to work from the phone. Make it very simple to connect through
Cloudflare, right from the TV." The owner chose a tunnel with an own domain on Cloudflare, and `cloudflared` in the
APK.

<!-- STE note: the scenarios use the Given, When, Then form of all specs in this repository. -->

## Context

Spec 011 gives an MCP server on the local network. claude.ai and the Claude mobile app connect to a custom connector
from the Anthropic cloud, not from the phone. So the server needs a public HTTPS address on port 443, and OAuth
(research on 2026-11-25, `research.md`). MCP tunnels of Anthropic are for the Enterprise plan only.

## User Scenarios & Testing

### User Story 1 - Turn on access from claude.ai on the TV (Priority: P1)

The user creates a tunnel in the Cloudflare dashboard with a public hostname to `http://localhost:8766`. In Telik, the
user turns on "Access from claude.ai". Telik shows a QR code. The user scans it with the phone and pastes the tunnel
token on the page that opens. Telik starts the tunnel and shows the public address and its state.

**Why this priority**: Without the tunnel, claude.ai cannot reach the TV.

**Independent Test**: Turn the setting on, set the token from the phone, and open `https://<host>/.well-known/oauth-authorization-server`
on the phone.

**Acceptance Scenarios**:

1. **Given** the setting is off, **When** the TV starts, **Then** no tunnel runs.
2. **Given** the setting is on and no token is set, **When** the user opens the setting, **Then** Telik shows a QR
   code with a local address and a one-time code.
3. **Given** the page of the QR code, **When** the user pastes a valid token, **Then** Telik keeps it and starts the
   tunnel. **When** the code is wrong or old, **Then** the page refuses the token.
4. **Given** a running tunnel, **When** it connects, **Then** the settings show "Connected" and the public address.
5. **Given** the tunnel process stops, **When** the setting is still on, **Then** Telik starts it again after a delay.

---

### User Story 2 - Connect claude.ai and confirm on the TV (Priority: P1)

The user adds a custom connector in claude.ai with the address `https://<host>/mcp` and selects Connect. The phone
or browser opens a Telik page that says "Confirm on the TV". The TV shows "Claude wants to control this TV" with the
same code, and OK and Back. After OK, claude.ai has access. The connector then works in the Claude mobile app.

**Why this priority**: This is the goal of the owner: control from the phone.

**Independent Test**: Add the connector in claude.ai. Confirm on the TV. Ask Claude on the phone "what plays on the TV?"

**Acceptance Scenarios**:

1. **Given** a request without a token from the internet, **When** it reaches `/mcp`, **Then** Telik answers 401 with
   the address of its OAuth metadata.
2. **Given** an authorization request with a redirect address that is not of Claude or of a local loopback, **When**
   it comes, **Then** Telik refuses it.
3. **Given** a valid authorization request, **When** the user presses OK on the TV, **Then** the page continues to
   claude.ai with a one-time code. **When** the user presses Back or waits 2 minutes, **Then** the request fails.
4. **Given** a code, **When** claude.ai exchanges it with the correct PKCE verifier, **Then** it gets an access token
   (1 hour) and a refresh token. A second exchange of the same code fails.
5. **Given** an access token, **When** claude.ai calls the tools, **Then** they work as on the local network.

---

### User Story 3 - Remove access (Priority: P2)

The user selects "Disconnect all agents" in Telik. All tokens of claude.ai stop working. The user can also turn the
tunnel off.

**Acceptance Scenarios**:

1. **Given** connected agents, **When** the user disconnects all, **Then** each next request gets 401.
2. **Given** the setting is turned off, **When** it is off, **Then** the tunnel process stops.

### Edge Cases

- The token of the tunnel is wrong: the tunnel does not connect, and the settings show the error line of cloudflared.
- No internet: cloudflared tries again. The local server of spec 011 still works.
- The TV is in standby with the network on: the tunnel stays up with the Telik service.
- Many authorization requests: at most 3 wait on the TV at a time. Telik refuses more.

## Requirements

### Functional Requirements

- **FR-001**: Telik MUST include a pinned `cloudflared` build for armeabi-v7a, checked by SHA-256 at build time.
- **FR-002**: Telik MUST run the tunnel only while the setting "Access from claude.ai" is on and a tunnel token is
  set. The default is off. The MCP server of spec 011 must be on too.
- **FR-003**: The tunnel token MUST be set from the phone through a local page with a one-time code from the TV
  screen, or through adb. It MUST be kept in private storage. Telik MUST never show it or log it.
- **FR-004**: Telik MUST serve OAuth 2.1 for remote clients: protected resource metadata (RFC 9728), authorization
  server metadata (RFC 8414), dynamic client registration (RFC 7591), the authorization code grant with PKCE S256, and
  rotating refresh tokens.
- **FR-005**: The redirect addresses MUST be the callback of Claude (`https://claude.ai/api/mcp/auth_callback`,
  `https://claude.com/api/mcp/auth_callback`) or a loopback address.
- **FR-006**: Each authorization MUST be confirmed by a key press on the TV.
- **FR-007**: Requests through the tunnel MUST have an OAuth access token or the token of spec 011. The other rules of
  spec 011 stay for local requests.
- **FR-008**: Telik MUST keep only SHA-256 hashes of access and refresh tokens.
- **FR-009**: The user MUST be able to remove all OAuth grants.

## Success Criteria

- **SC-001**: From a new install, the user connects claude.ai in less than 5 minutes after the tunnel exists in
  Cloudflare.
- **SC-002**: Claude in the mobile app reads the media state of the TV and pauses it.
- **SC-003**: Unit tests cover PKCE, the redirect check, code reuse, expiry and refresh rotation.

## Assumptions

- The user has a Cloudflare account and a domain on it. Telik does not create the tunnel. The docs show the steps in
  the dashboard.
- The APK gets bigger by the size of `cloudflared` (about 37 MB before compression). The owner accepted this.
- Constitution I: the tunnel runs on the TV. Cloudflare and claude.ai are services, not extra devices.
