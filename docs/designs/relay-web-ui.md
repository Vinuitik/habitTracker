# Relay web UI: bring back what the Android app had

Status: PLAN (2026-11-03). Hand-off doc for an implementing agent.

## Key fact
Relay never had a web UI. Containers, git, model and effort selection, and subagents all lived in the **Android app** (`~/Documents/Relay/android/.../ui/screens/*.kt`). The `/relay` web page in habitTracker was a minimal port of that app. The **Go runner already implements every feature**, and the hub proxy (`relay/RelayProxyController`) forwards any `/relay/api/devices/{id}/v1/**` call untouched. So this work is **frontend only**.

## Hard constraints
- **Do not touch databases.** That means no Mongo schema changes, no new collections, and no drop or deleteAll calls. Leave `relay_devices`, `relay_trusted_devices` and the project link fields on `projects` as they are. That keeps the existing runner pairing and the user's login intact.
- Do not change pairing, 2FA or uplink code (`relay/` Java). If you hit a backend need, stop and escalate.
- Follow the habitTracker design system (CLAUDE.md): Focused Dark tokens in `styles/tokens.css` and atomic CSS/JS. Every URL lives in `js/env.js`. No inline styles. No `!important`.
- Color meaning, borrowed from Relay's "Signal Box" language:
  - `--color-accent` (indigo) marks actions.
  - `--color-warning` (amber) means **"needs you"** and nothing else.
  - success green means running or done.
  - Status lamps are 8px dots: filled when lit, outline when off. A 4px amber left bar marks cards that block the agent (permission, quota, login).
- `sw.js` must **never cache** `/relay/api/**` polls. There is precedent: decision 38 in `DECISIONS-relay-ai.md`.
- Destructive actions (git push, compose down, branch switch with a dirty tree) need an inline confirm. Reuse the confirm in `js/atoms/menu.js`.
- Runner API contract: `~/Documents/Relay/shared/API.md`. The Android screens are behavior reference only. Don't copy their look.

## Reuse
- **JS** (`js/atoms`): `api.js`, `relay-api.js` (401/503/504 mapping), `dom.js`, `icons.js`, `menu.js`, `toast.js`.
- **JS molecules**: `composer.js`, `step-list.js`, `folder-picker.js`.
- **Styles**: badge, button, input, modal, seg-tabs, menu, step-list, composer.

## Milestones
Each milestone is one agent, gets one commit, and is verified at desktop and 400px width.

### M0: Split relay.js (no visual change)
`js/relay/relay.js` (566 lines) becomes `js/relay/router.js` plus organisms:
- `relay-devices.js`
- `relay-chat.js`
- `relay-install.js`

Add URL builders for every runner route below to `env.js` (`RELAY_V1(dev, path)`).
Done when: the current page behaves exactly as before.

### M1: Shell and navigation (replaces the `<select>`)
1. Start with a design pass: `/design-shotgun` produces 2-3 shell variants, and the user picks one before you build.
2. **Desktop** (≥900px), left rail of 260px:
   - Runner switcher with an online lamp.
   - Project list with per-project lamps: busy (green) and needs-you (amber).
   - "+ Project", which takes a name or browses for a folder via `folder-picker.js` → `POST /v1/projects {name, path?}`.
3. **Mobile**: list-first drill-down, runner → projects → project.
4. A "Needs you" strip at the top: `GET /v1/sessions?state=waiting,busy`.
5. The project view header has tabs: **Chats · Git · Containers · Files**. Use hash routes `#/d/<dev>/p/<pid>/<tab>`.

### M2: Chats and chat polish
1. **Chats tab**:
   - Relay sessions (`GET /v1/projects/{id}/sessions`).
   - Laptop/VS Code chats (`GET /v1/projects/{id}/agent-chats`), resumable via `POST .../sessions {provider, agentSessionId}`.
   - A "New chat" button.
2. **Model and effort chip** above the composer, e.g. "Sonnet · High":
   - Opens a popover listing `session.configOptions` grouped by category (`model`, `thought_level`, fast).
   - Choosing an option sends `POST /v1/sessions/{sid}/config {configId, value}`.
   - The chip is disabled while busy.
   - The permission mode selector sits beside it: `POST .../mode {modeId}`.
3. **Blocking cards** (amber bar):
   - Permission: `POST .../permission {optionId}`, keeping the existing flow.
   - Quota and login problems.
4. Unsent drafts are saved per session in localStorage, wrapped in try/catch.
5. Polling: every 1s while busy, every 3s while waiting, every 10s when idle.

### M3: Subagents panel
1. A collapsible bar pinned above the chat that reads "2 subagents running".
2. Expanding it shows one tab per agent, with the selected agent's feed read-only.
3. Endpoints: `GET /v1/sessions/{sid}/agents`, then `GET .../agents/{agentId}/messages?after=n`, paged and polled only while the panel is expanded.

### M4: Git tab
Everything is under `/v1/projects/{id}/git`.
1. **Status**: staged and unstaged file lists. Clicking a file shows its diff (`/diff?path=&staged=`) in mono with green and red line backgrounds.
2. **Staging**: stage and unstage per file and all at once (`POST /stage`, `/unstage`).
3. **Commit**: a commit box (`POST /commit`).
4. **Branches**: a switcher with create (`/branches`, `POST /switch`).
5. **Push/pull/fetch** (202 response): poll until done, show a step row, then toast.
6. **History**: the last 30 commits (`/log?limit=30`).
7. Large diffs: the proxy caps bodies at 4 MiB. Truncate in the UI and show "diff too large".

### M5: Containers tab
1. **Compose files**: list them and their containers (`GET /v1/projects/{id}/containers`). Each container shows a state lamp.
2. **Active file**: a selector for which compose file is active.
3. **Start**: `POST .../containers/start {file?}`.
4. **Stop**: `POST .../containers/stop`, behind a confirm.
5. Both actions run in the background. Poll until `operation === ""`.
6. No logs: the runner API doesn't have them. Mark this `[NOT IMPLEMENTED]`.

### M6: Files tab (read-only, cheap)
1. **Tree**: `GET /v1/projects/{id}/files?path=`.
2. **Viewer**: `.../files/content?path=` in a mono viewer.

### M7: QA and docs
1. `/qa` on desktop and phone width, against the real paired runner (`victor-Aspire-A715-75G`).
2. Update `relay/FLOWS.md`: remove the "Not in the UI" line and add these flows.
3. Run `graphify update .`.
4. Confirm the `sw.js` cache version bump.

## Later (not in this plan)
Usage and tokens, sleep/wake schedule, VS Code permission cards, handoff between machines, multi-runner management, sign-in relay. All of these exist in the runner API.

## Suggested model split
- **M0 + M1** need design judgment: Opus.
- **M2 to M6** are mechanical against a clear API: Sonnet.
  - After M0, M4, M5 and M6 touch separate files and can run in parallel worktrees.
  - M2 and M3 share `relay-chat.js`, so run them in sequence.
- **M7**: Sonnet.
