# Feature Specification: Agent control of the TV (MCP)

**Feature Branch**: `andrewkomkov/tv-mcp`

**Created**: 2026-11-24

**Status**: Draft

**Input**: The coordinator task of 2026-11-24: "add to Telik an MCP server through which an agent (Claude Code and
others) controls the TV: goes through the apps and understands what is on the screen". The minimum: the app list and
app launch; the current app, the UI tree and a screenshot; D-pad, Back, Home, a click on an element, text input and
scroll; a wait for a change of the screen after an action. Off by default, on in the settings, a token, the LAN only.

<!-- STE note: the scenarios use the Given, When, Then form of all specs in this repository. -->

## User Scenarios & Testing

### User Story 1 - The agent sees the TV screen (Priority: P1)

The user turns on agent control in the Telik settings and connects an agent on a computer in the same network. The
agent asks what is on the screen. It gets the current app and the elements of the screen with their text, role,
focus and position. Telik gives no pictures of the screen (decision of the owner on 2026-11-24): the agent works with
structured data only.

**Why this priority**: The agent cannot act without a view of the screen. This view also helps alone: for example, to
read a menu or to check a test.

**Independent Test**: Turn the setting on. Connect the agent. Ask for the screen on the Telik Home screen and in
YouTube. Compare the answer with the TV.

**Acceptance Scenarios**:

1. **Given** agent control is on, **When** the agent asks for the screen, **Then** it gets the package and the
   activity of the current app, and the elements with text, description, role, focus, state and bounds.
2. **Given** an app that draws its own UI (YouTube, Apple TV), **When** the agent asks for the screen, **Then** the
   answer says that the app gives no tree, and points to deep links, the media session and the keys.

---

### User Story 2 - The agent opens apps and moves through them (Priority: P1)

The agent gets the list of installed TV apps and opens one. It presses D-pad keys, OK, Back and Home, clicks an
element of the screen, types text in a field and scrolls a list.

**Why this priority**: This is the control itself. With US1 it gives the full loop: look, act, look again.

**Independent Test**: The agent opens YouTube, finds the search element, clicks it, types a word, and goes back to the
Home screen.

**Acceptance Scenarios**:

1. **Given** agent control is on, **When** the agent asks for the apps, **Then** it gets the launchable TV apps with
   name and package.
2. **Given** the list, **When** the agent opens an app, **Then** the app comes to the front.
3. **Given** a screen, **When** the agent presses up, down, left, right, OK, Back or Home, **Then** the TV does the
   same as for the remote key.
4. **Given** an element from the last screen answer, **When** the agent clicks it, **Then** the TV clicks that element.
   If the element is gone, the agent gets an error that tells it to read the screen again.
5. **Given** a text field, **When** the agent types text, **Then** the text is in the field.
6. **Given** a list that scrolls, **When** the agent scrolls it, **Then** the list moves.

---

### User Story 3 - The agent waits for the result of an action (Priority: P2)

After an action the screen changes with a delay: an app starts, a list loads. The agent waits until the screen
changes and stops changing, or until a given text shows, and then reads the screen.

**Why this priority**: Without a wait the agent reads the old screen or waits for a fixed time. Both are slow or wrong.

**Independent Test**: The agent opens YouTube and waits for the text "Search" or for a quiet screen. Measure the time
against the TV.

**Acceptance Scenarios**:

1. **Given** an action, **When** the agent waits for a change, **Then** the wait ends after the screen changed and was
   quiet for a short time, or at the time limit.
2. **Given** a text, **When** the agent waits for it, **Then** the wait ends when an element with that text shows, or
   at the time limit with a "not found" result.

---

### User Story 4 - Safe by default (Priority: P1)

Agent control is off after install. When the user turns it on, Telik makes a secret token and shows it with the
address and a ready command for the agent. A request without the token, or from outside the local network, fails.

**Why this priority**: The feature lets a program press keys on the TV. It must not be open to the network.

**Independent Test**: Send requests with no token, a wrong token, and the correct token. Turn the setting off and
send a request again.

**Acceptance Scenarios**:

1. **Given** a new install, **When** a device in the network connects to the port, **Then** nothing listens.
2. **Given** agent control is on, **When** a request has no token or a wrong token, **Then** it fails with
   "unauthorized" and does nothing.
3. **Given** agent control is on, **When** a request comes from an address that is not local, **Then** it fails.
4. **Given** a web page in a browser of the network, **When** it sends a request, **Then** it fails.
5. **Given** the user makes a new token, **When** the agent uses the old one, **Then** it fails.

### Edge Cases

- Accessibility access of Telik is off: the screen and action requests fail with a clear message. The app list and
  app launch still work.
- The screen has many elements: the answer has a limit and says that it was cut.
- Two agents send requests at the same time: Telik answers both, in order for the actions.
- The TV sleeps: the server stops with the Telik service and starts again with it.
- An element id from an old answer: the click fails with "stale, read the screen again".
- The port is busy: Telik logs it and the settings show the server as not running.

## Requirements

### Functional Requirements

- **FR-001**: Telik MUST give an agent the list of launchable TV apps (name, package) and MUST open an app by package.
- **FR-002**: Telik MUST give the current app (package, activity when known) and the UI tree of the active windows:
  for each element an id, the text, the description, the role, the resource id, the bounds, and the states focused,
  clickable, editable, scrollable, selected, checked.
- **FR-003**: Telik MUST NOT give screenshots. For apps without a tree, Telik MUST give structured paths: deep links,
  TV Provider rows, the media session, and tested steps for each main app.
- **FR-004**: Telik MUST press D-pad up, down, left, right, OK, Back and Home, and the media keys play/pause and volume.
- **FR-005**: Telik MUST click an element by its id, and a point by its coordinates.
- **FR-006**: Telik MUST type text into the focused field or an element by id, and can submit it.
- **FR-007**: Telik MUST scroll an element, or the first element that scrolls, in a direction.
- **FR-008**: Telik MUST wait for a change of the screen, or for a text, with a time limit of at most 30 seconds.
- **FR-009**: Agent control MUST be off by default and MUST be turned on by the user in the Telik settings.
- **FR-010**: Each request MUST have the bearer token. The token MUST have 128 random bits, MUST be separate from the
  Shortcuts token, and the user MUST be able to make a new one.
- **FR-011**: Telik MUST refuse a client with an address that is not loopback, link-local or private, and a request
  with a browser `Origin` header.
- **FR-012**: The server MUST use the open MCP protocol over HTTP, so that Claude Code and other MCP clients connect
  without extra software.
- **FR-013**: The log MUST have the tool name and the status only, never the token, the typed text or screen text
  (constitution III).
- **FR-014**: A developer with adb MUST be able to set the token and turn the setting on from the computer, so that a
  token does not go through a screen photo.

### Key Entities

- **Screen**: the current app, a version number that grows on each change of the screen, and a list of elements.
- **Element**: an id valid until the next screen answer, text, description, role, bounds, states.
- **Token**: the secret of the agent server, kept in Telik storage that the settings export of adb does not show.

## Success Criteria

### Measurable Outcomes

- **SC-001**: An agent opens YouTube, finds and clicks an element, and goes back to the Home screen without help.
- **SC-002**: A screen answer comes in less than 1 second on the test TV for a screen of the Home screen or YouTube.
- **SC-003**: All requests without the correct token fail, in a unit test and on the TV.
- **SC-004**: The connection needs one command on the computer, which Telik shows in the settings.

## Assumptions

- The agent runs on a computer in the same local network as the TV. Telik does not go through the internet.
- Accessibility access of Telik is on, as for the iPhone remote.
- The test TV has Android 14 (API 34). Screenshots of the accessibility service need API 30 or later.
- An adb-based server on the computer is out of scope: constitution I says that the user installs nothing else, and
  the server in Telik works without adb.
- Video content with protection stays protected (constitution II).
