# Project Flows

Files: `Project.java`, `ProjectRepository.java`, `ProjectService.java`, `ProjectController.java`, `ProjectDeleteTokenService.java`, `ProjectView.java`, `BoardSnapshot.java`, `BoardSnapshotRepository.java`, `BoardSnapshotService.java`, `BoardSyncScheduler.java`, `ProjectBoardService.java`, `ProjectRunnerController.java`, `ProjectRunnerService.java`, `ProjectMap.java`, `ProjectMapRepository.java`, `resources/prompts/project-map.md`, `PlannerController.java`, `PlannerService.java`, `PlannerPrompts.java`, `PlannerConversation.java`, `PlannerConversationRepository.java`, `resources/prompts/planner-clarify.md`, `resources/prompts/planner-draft.md`. Direct Trello access: see `../integrations/trello/FLOWS.md`. Runner transport: see `../relay/FLOWS.md` (Runner client).

A Project (name, description, userId, trelloBoardId, createdAt, imported; optional runner link: runnerDeviceId, runnerProjectId, folderName, mapJobId) is 1:1 with a Trello board. Collection `projects`.

## Runner link (local folder on the user's Relay runner)
`POST /api/projects/{id}/link {deviceId, path}` → `ProjectRunnerController.link()` → `ProjectRunnerService.link()` → `owned()` (404) → blank deviceId/path → 400 → `register()` → `RunnerJobService.send(POST /v1/projects {path})` → 201 `{id, name}`; runner 400/409 → `GET /v1/projects` (array or `{projects:[]}`) → entry whose `path` equals (trailing slash ignored) → reuse; none → original runner error → save `runnerDeviceId`, `runnerProjectId`, `folderName` (runner `name`, else last path segment), clear `mapJobId` → Project JSON
`DELETE /api/projects/{id}/link` → `ProjectRunnerService.unlink()` → null the 4 fields → Project JSON. The runner keeps the folder registered; the stored map is kept.
- Device ownership is enforced inside `RunnerClient.call()` (another user's / deleted device → 409 `runner_not_found`).
To change: path match `ProjectRunnerService.samePath()`, display name `lastSegment()`.

## Repo map (built by Claude Code on the runner)
`POST /api/projects/{id}/map` → `ProjectRunnerService.startMap()` → `owned()` → not linked → 409 `project_not_linked` → `RunnerJobService.startJob(kind="map", projectId=runnerProjectId, prompt=prompts/project-map.md, maxTurns)` → save `Project.mapJobId` → 202 `{jobId}`
Prompt: survey repo → split into 3-12 subsystems → ONE subagent per subsystem in parallel (haiku/sonnet), each returns FLOWS-style notes (Files: line, arrow chains, Change Index, `[NOT IMPLEMENTED]`) → synthesize index + cross-subsystem links → commitSha from `.git/HEAD` + refs/packed-refs (no Bash) → final message = only `{commitSha, index, subsystems:[{name, paths, notes}]}`. To change: `src/main/resources/prompts/project-map.md`; turn cap `relay.map.max-turns` (200).
`GET /api/projects/{id}/map/job/{jobId}?after=N` → `ProjectRunnerService.pollMap()` → `owned()` + linked → jobId ≠ `Project.mapJobId` → 404 → `RunnerJobService.pollJob()` → runner snapshot passed through as-is → if `state=done`: stored map already has this jobId → `mapStored:true` (no rewrite); else `parse(result)` (object, or text: first `{` … last `}`, so code fences/prose are tolerated; needs ≥1 subsystem with a name, ≤100) → upsert `project_maps` (same `_id` per project) with `builtAt=now` → `mapStored:true`; unusable result → `mapStored:false, mapError` (nothing stored, WARN log)
`GET /api/projects/{id}/map` → `ProjectRunnerService.mapSummary()` → `{commitSha, builtAt, subsystems:[{name, paths}]}` (index + notes not returned) or 404 `{error:"no map yet"}`
- Every poll → `AiUsageService.record(…, Source.map(projectId))`: first terminal snapshot with `usage` → one `ai_usage` row (see `aiusage/FLOWS.md`).
- Project delete also deletes its `project_maps` doc (`ProjectService.delete()`).
- Errors (ProjectRunnerController): runner_not_found 409 · runner_offline 503 · runner_timeout 504 (all with `message:"Connect a runner"`) · runner 4xx passthrough / 5xx → 502 `{error:"runner_error", runnerStatus, detail}` · bad input 400.
- Full map (index + notes) is read only by the planner prompt (below); staleness check vs current commit [NOT IMPLEMENTED].

## Planner chat (runner "plan" jobs → Trello)
Collection `planner_conversations` {projectId, userId, phase CLARIFY|DRAFT, messages[{role user|ai, text, questions?}], questions, draft, currentJobId, resultJobId, error, createdAt, updatedAt, appliedAt, appliedDeadline, appliedPace}. Requires a linked folder (else 409 `project_not_linked`).
`POST /api/projects/{id}/planner {message, conversationId?}` → `PlannerController.message()` → `PlannerService.message()` → `owned()` + linked → blank / >8000 chars → 400 → new conversation, or `ownedConversation()` (id+projectId+userId, else 404) + `ensureIdle()` (job still running → 409 `planner_busy`) → append user message, phase=CLARIFY → `startJob()` → `PlannerPrompts.build()` → `RunnerJobService.startJob(kind="plan", runnerProjectId, prompt, maxTurns)` → save (only after the runner accepted: a refused start saves nothing) → `{conversationId, jobId}`
`POST …/planner/{cid}/lock` → `PlannerService.lock()` → idle check → phase=DRAFT → `startJob()` (same history, draft template) → `{conversationId, jobId}`
`GET …/planner/{cid}?after=N` → `PlannerService.poll()` → only `currentJobId` is polled, and only while ≠ `resultJobId` → `RunnerJobService.pollJob()` → events mapped to `{seq,kind,text}` → `done` → `storeResult()`: `extractObject()` (object, or text: first `{` … last `}`) → CLARIFY: `{reply, questions≤3}` → ai message (+questions) / DRAFT: `parseDraft()` → draft + ai message → `applyOnce()` = conditional update (`currentJobId=job AND resultJobId≠job`) so it's stored once; unusable result → `error` / `failed|error|cancelled` → `error` / runner 404 for the job → `error` ("no longer exists") → `{state, events, nextSeq, messages[{role,text}], questions?, draft?, error?}`
- Every runner poll → `AiUsageService.record(…, Source.plan(projectId))`; the poll that sees the job finish also returns `usage {inputTokens, outputTokens, totalTokens}` (UI: "Used N tokens").
- state: job not stored → `running`; error → `failed`; DRAFT + draft → `drafted`; else `waiting_user`. To change: `PlannerService.state()`.
- Draft validation: ≥1 list, ≤20 lists, ≤150 cards, list name + card title required (else unusable/400); importance 1..3 (numbers clamped, must/should/could words, default 2); dates cut to YYYY-MM-DD, invalid due → dropped, invalid schedule row → skipped. To change: `PlannerService.parseDraft()/importance()/date()`.
Prompt = template + `{{TODAY}}`, `{{PROJECT_NAME}}`, `{{PROJECT_DESCRIPTION}}`, `{{MAP}}`, `{{BOARD}}`, `{{HISTORY}}` (one-pass substitution: user text can't inject placeholders)
- MAP: `PlannerPrompts.mapSection()` = map index + each subsystem's name/paths/notes, cap `relay.plan.map-max-chars` (60000): over the cap, notes are dropped (name+paths kept) least-relevant first (keyword overlap of the user's messages with name/paths ×3, notes ×1; ties: bigger notes first), then hard truncation. No map → "explore the folder" note.
- BOARD: `BoardSnapshotService.get(p, false)` (Trello failure → cached snapshot, else "(board not available)") → open lists + their open, not-done cards (+ due date), cap `relay.plan.board-max-chars` (20000).
- HISTORY: User/Assistant turns incl. asked questions, oldest dropped first past `relay.plan.history-max-chars` (30000).
- Templates instruct: map first, Read files only when needed, parallel haiku/sonnet subagents for multi-area research, output ONLY the JSON. To change: `resources/prompts/planner-clarify.md`, `planner-draft.md`; turn cap `relay.plan.max-turns` (80).
`POST …/planner/{cid}/apply {draft?, deadline?, pace?}` → `PlannerService.apply()` → `owned()` (link not required) + idle → draft = body `draft` (validated by `parseDraft()`) else stored draft, none → 400 → bad deadline → 400 → per list: open board list with same name (ci, from the cached snapshot) reused, else `TrelloApiClient.createList(pos=bottom)` → per card: `TrelloApiClient.createCard {idList, name, desc + ```meta importance: N```, pos=bottom, due}` → due = card.due, else schedule date (by title, ci), else deadline, as `<date>T12:00:00.000Z` → save draft/appliedAt/deadline/pace (`appliedAt` set → any later apply on this conversation → 409 `{error:"planner_already_applied"}`, checked before any Trello write; UI shows "Already added to the board") → `BoardSnapshotService.get(p, true)` (failure only logged) → `{created: <cards>}`. The only Trello write of the planner.
- `pace` is stored only [NOT IMPLEMENTED: no effect on dates]; the schedule comes from the draft job.
- Errors: runner 409/503/504 `{error:runner_*, message:"Connect a runner"}` · runner 4xx passthrough / 5xx → 502 · Trello 412 `trello_not_connected|trello_token_invalid` · 429 `trello_rate_limited` · other Trello → 502 `trello_error`. To change: `PlannerController` handlers.
- Project delete removes its conversations (`ProjectService.delete()`).

## CRUD + isolation
`/api/projects` → `ProjectController` → `ProjectService` → `SecurityUtils.getCurrentUserId()` → `ProjectRepository.findByUserId` / `findByIdAndUserId`
- By-id access never uses `findById`; another user's id yields 404, same as a missing one (no IDOR). To change: `ProjectService.owned()`.
- List: `ProjectService.list()` → `ProjectView` = Project fields + `cardCount`/`doneCount` (non-closed cards of the snapshot) + `syncedAt`; null if never synced. To change: `ProjectView.of()`.
- Create: blank `trelloBoardId` → `TrelloApiClient.createBoard(userId, name)` (user's own Trello, no snapshot yet); else `TrelloApiClient.getBoard()` validates → `imported=true` + snapshot stored. To change: `ProjectService.create()`.

## Import + board mirror
`GET /api/projects/trello/boards` → `ProjectService.trelloBoards()` → `TrelloApiClient.listMyBoards()` → `[{id,name,url,imported}]` (imported = user already has a project with that boardId)
`POST /api/projects/import {trelloBoardIds}` → `ProjectService.importBoards()` → skip ids already linked/duplicated → `getBoard()` → `Project(imported=true, name=description=board name)` → `BoardSnapshotService.store()` → `[Project]` (201)
`GET /api/projects/{id}/board?refresh=false` → `ProjectService.board()` (`owned()`) → `BoardSnapshotService.get()` → fresh (<60s) cached snapshot, else (snapshot exists, !refresh) `syncIfChanged()` = `TrelloApiClient.getBoardActivity()` probe: `dateLastActivity` equal → bump `syncedAt` only; differs/unknown → `getBoard()` → map → upsert `trello_board_snapshots` → `{projectId,boardId,name,url,syncedAt,boardMissing,lists,cards}`. `refresh=true` or no snapshot → full `getBoard()` (no probe).
- Probe/fetch 404 with a snapshot → `boardMissing=true` (project kept; cleared by the next successful probe/fetch). Any other Trello failure with a snapshot → stale. To change: `BoardSnapshotService.get()/syncIfChanged()/markMissing()`.
- Trello failure with an existing snapshot → snapshot returned with `X-Stale: true`; without → 502.
- Mapping: list `_meta` and its cards dropped; card `done` = label named "done" (ci) OR list named Done/Completed (ci). To change: `BoardSnapshotService.map()`, TTL `BoardSnapshotService.TTL_SECONDS`.
- Errors: no creds → 412 `{error:"trello_not_connected"}`; Trello 401 → 412 `{error:"trello_token_invalid"}`. To change: `ProjectController` handlers.

## Background sync (Trello-side edits)
`BoardSyncScheduler.syncScheduled()` (cron `0 */15 * * * ?`, hardcoded) → `syncAll()` → `BoardSnapshotRepository.findAll()` grouped by `userId` → skip users without `TrelloCredential` → per board `ProjectRepository.findById` → `BoardSnapshotService.syncIfChanged()` (probe `dateLastActivity`; changed → full fetch + upsert; 404 → `boardMissing=true`) → `pauseMs` (200) sleep.
- Isolation: any exception on a board is logged and the loop continues. Trello 401 / not connected → skip that user's remaining boards. Trello 429 → stop the whole run. Not run on startup.
- To change cadence: `@Scheduled` cron in `BoardSyncScheduler`; Trello call spacing: `BoardSyncScheduler.pauseMs`.

## Board editing (write-through)
`POST /api/projects/{id}/lists {name,pos?}` · `PATCH .../lists/{listId} {name?,pos?,closed?}` · `POST .../cards {idList,name,desc?,pos?}` · `PATCH .../cards/{cardId} {name?,desc?,due?,dueComplete?,idList?,pos?,closed?}` · `POST .../cards/{cardId}/complete` · `POST .../cards/{cardId}/reopen` → `ProjectController` → `ProjectBoardService.<op>()` → `owned()` (404) → validate (blank name / empty PATCH / bad pos → 400) → `ensureList()/ensureCard()` → `TrelloApiClient` write → `BoardSnapshotService.patchList()/patchCard()` → list view `{id,name,pos,closed}` or card view (CardView, incl. `done`)
- Integrity: id is accepted if present in the cached snapshot, else Trello `getList/getCard` and `idBoard` must equal the project's board; mismatch or Trello 404 → 404. To change: `ProjectBoardService.ensureList()/ensureCard()/checkBoard()`.
- PATCH sends only keys present in the body; `"due":null` → Trello `due=null` (clears); `pos` = number or "top"/"bottom". To change: `ProjectBoardService.updateCard()`.
- complete: `getCard` → ensure board label "done" (`getBoardLabels`, else `createLabel` green) → `addCardLabel` (skipped if already on card) → if an open list named Completed (preferred) or Done exists → `updateCard(idList,pos=top)` → `getCard` → patch. Never creates lists. reopen: `removeCardLabel` for "done" only, no move. To change: `ProjectBoardService.complete()/completedList()`.
- Patch: card/list replaced by the Trello response via the same `mapCard` as board sync, `done` recomputed (list rename recomputes its cards); card landing in an unknown/_meta list is dropped; no snapshot yet → no patch. `syncedAt` is NOT bumped, so the 60s TTL still decides when GET /board refetches. To change: `BoardSnapshotService.patchCard()/patchList()`.
- Errors as board read, plus Trello 429 → 429 `{error:"trello_rate_limited"}` + `Retry-After` passthrough. To change: `ProjectController.upstream()`.

## Merge boards (move list between boards)
`POST /api/projects/{id}/lists/{listId}/move {targetProjectId, pos?}` → `ProjectController.moveList()` → `ProjectBoardService.moveList()` → `owned(source)` + `owned(target)` (404 if not the caller's; missing targetProjectId or same project/board → 400) → `ensureList(source, listId)` (404) → `TrelloApiClient.moveListToBoard()` (pos default "bottom"; numeric pos re-sent via `updateList` if Trello's answer differs) → `BoardSnapshotService.get(target, refresh=true)` + `get(source, refresh=true)` (source failure only logged) → target `BoardSnapshot` JSON.
- Reorder within a board is the existing `PATCH .../lists/{listId} {pos}`.
- To change: `ProjectBoardService.moveList()`.

## Error handling / logging
`ProjectController.upstream()` logs every `TrelloGatewayException` once at WARN (status, message) and maps 429/409/502. `ProjectController.unexpected()` = catch-all `RuntimeException` → ERROR with stack + 500 `{error:"internal_error"}`; it first maps ErrorResponse (own status), IllegalArgument/message-conversion/type-mismatch (400) and rethrows Spring Security exceptions, because a controller-local RuntimeException handler would otherwise shadow `GlobalExceptionHandler` and Spring's MVC resolvers.

## Two-step delete
`POST /api/projects/{id}/delete-request` → `ProjectService.requestDelete()` (ownership check) → `ProjectDeleteTokenService.issue()` → `{token}` (60s)
→ `DELETE /api/projects/{id}?token=` → `ProjectService.delete()` → `redeem(token, projectId, userId)` (atomic remove = single-use) → (`imported` → skip Trello; else `TrelloApiClient.deleteBoard()`, 404 = already gone) → `BoardSnapshotService.delete()` → `repository.deleteById()`
- Missing/wrong/expired/reused/cross-project token → 403. Imported projects are only unlinked (the user's Trello board survives). Otherwise the board is deleted before the Mongo row so a Trello failure leaves the project retryable.
To change TTL: `ProjectDeleteTokenService.DEFAULT_TTL_MS`.

## Technology Notes
- Sync cadence: 15-min background job + the frontend's 60s poll while a board is open (which only hits Trello once the 60s TTL lapses). Worst-case latency for a Trello-side edit: ~15 min unattended, ~1-2 min with the board open.
- `dateLastActivity` probe semantics: Trello bumps it on any board activity (card/list/label/member edits, comments), so it can differ without a visible change (extra full fetch, harmless); equal means no activity and the snapshot is kept. It is a string compare, not a clock compare. A snapshot with no stored marker (pre-Stage 3) is always refetched once.
- Scheduler is in-process and single-node: two javaapp instances would each probe every board (no lock); `@Scheduled` runs on Spring's single scheduler thread, so a slow run delays the other cron jobs (updater, mailbox consume). Cost per run = 1 probe request per board + 1 fetch per changed board, spaced 200ms.
- Missed updates self-heal: a failed/skipped run, restart, or 429 just means the next probe (cron or GET /board after TTL) compares against the same stored marker and catches up.
- `boardMissing` is set on any Trello 404 for the board (deleted, closed-and-inaccessible, or access lost); the project is never auto-deleted. Unlink = the normal two-step delete (`imported` projects only unlink).
- Webhooks [NOT IMPLEMENTED]: polling only; webhooks would need a public callback URL and per-board registration.
- Edits are synchronous write-through: each costs ~300-800ms per Trello call (complete = 4-6 calls). No retries; a 429 is returned to the client (with Retry-After) and the UI decides.
- Snapshot patch can diverge: if Trello applied a write but the response was lost (timeout), the client sees 502 and the snapshot is unpatched; the next refresh (`?refresh=true` or TTL expiry) heals it. Patch is read-modify-write on the whole snapshot doc, so concurrent edits on one board can lose one patch until the next refresh.
- Membership check trusts the cached snapshot; a card moved to another board directly in Trello within the cache window could still be edited through this project.
- Delete tokens are in-memory (`ConcurrentHashMap`): lost on restart, single-node only, expired entries are only purged when redeemed (small leak of unredeemed tokens until restart).
- A failed redeem still consumes the token (remove-first); the user must request a new one.
- Cross-user ids return 404 (not 403) deliberately, to avoid leaking existence.
- Snapshot cache: 60s TTL, Mongo-backed (survives restart, shared across nodes), no invalidation besides `?refresh=true`; concurrent refreshes just upsert last-write-wins. Trello rate limit is ~100 req/10s per token (300/10s per key); each refresh is 1 request, import is 1 per board. No backoff: a 429 surfaces as 502 (or stale snapshot).
- Trello creds are per user (`trello_credentials`), apiKey+token stored **plaintext** in Mongo (not encrypted at rest, unlike `CapabilitySecret`). Token and key travel as query params to Trello (Trello's API design); never logged here.
- javaapp no longer depends on mongo-backup (old server-side plan/apply removed; planning runs on Relay runners). Trello down → create/import/delete 502; board view falls back to stale snapshot. Delete token is consumed before the Trello call, so a failed delete needs a new token.
- Delete order: board first, Mongo row second, so an upstream failure leaves a retryable project; a board deleted upstream but a crash before `deleteById` leaves a project pointing at a missing board.
- Moving a list is two non-atomic steps for us (Trello move, then snapshot refetch of both boards). If the refetch fails the board view self-heals on the next refresh. Moving a list to a board the token can't write to yields a Trello 4xx surfaced as 502. Labels are board-scoped in Trello; moved cards keep their labels — verified 2026-10-27: Trello copies the labels onto the target board (same name/color, new board-scoped id).
- No unique constraint on project name per user.
- **Repo map is only as good as the agent's final message:** the hub parses free text; a truncated or malformed JSON (long notes, bad escaping) yields `mapError` and nothing stored — re-run the map. The whole poll response must fit the 4 MiB uplink body limit, so a huge repo's map can fail with 502 (`response too large`).
- **Store-once is per jobId, not locked:** two concurrent polls of a just-finished job can both parse and upsert; same content, last write wins (`projectId` unique index prevents duplicates). Index is created in `ProjectRunnerService.init()` (auto-index-creation is off).
- **Only the latest map job is pollable** (`Project.mapJobId`); starting a new one orphans the old jobId (it keeps running on the runner; not cancelled). Relinking also clears it.
- **Map cost runs on the user's own Claude Code** (their plan/quota), N+1 agents per build; nothing is billed to the hub. Map jobs run with the runner's default model for the orchestrator; subagent models are chosen by the prompt.
- **commitSha comes from the agent reading `.git`**: a detached HEAD, packed refs or worktree (`.git` is a file) may yield an empty or wrong SHA; it's informational only.
- **runnerProjectId is the runner's local id:** re-pairing the runner or wiping its project registry invalidates it — relink.
- **Planner jobs run on the user's own Claude Code** (their quota); one orchestrator + whatever subagents the prompt asks for. Prompt size ≈ map cap 60K + board 20K + history 30K chars (~30K tokens) per job, re-sent every turn because each message is a fresh job (no runner-side session).
- **Planner conversation state is poll-driven:** a job's outcome is stored only when someone polls it. A conversation whose job is never polled stays `running` and blocks follow-ups/lock/apply (409 `planner_busy`) until polled; a runner that forgot the job (restart) yields 404 → `failed`. Starting a new job never cancels an old one (only possible while idle anyway).
- **Apply is not idempotent / not transactional:** cards are created one by one (2+ Trello calls per new list); a Trello failure mid-way leaves the cards created so far, and re-applying duplicates them. Nothing records which cards were created. List reuse trusts the cached snapshot (≤60s old or stale), so a list created in Trello since then may be duplicated.
- **Due time is fixed at 12:00 UTC** of the scheduled date to avoid off-by-one-day in most timezones; Trello shows it in the viewer's timezone.
- **Importance lives in a ```meta block** in the card description, the format the Trello MCP (`backup/trello_mcp/meta.py`) reads; editing the description by hand can break it.
- **Relevance ranking is naive keyword overlap** (words ≥4 chars); synonyms or a vague message may drop the notes the agent actually needs, which it then has to recover by reading files.

## Change Index
| What | Where |
|---|---|
| Background sync cadence / per-call pause / error policy | `BoardSyncScheduler` (`@Scheduled`, `pauseMs`, `syncUser()`) |
| Change probe, boardMissing marking | `BoardSnapshotService.syncIfChanged()/markMissing()`, `TrelloApiClient.getBoardActivity()` |
| Stored activity marker / missing flag | `BoardSnapshot.dateLastActivity`, `BoardSnapshot.boardMissing` |
| Board edit endpoints | `ProjectController` (lists/cards/complete/reopen), `ProjectBoardService` |
| Complete/reopen semantics, Completed/Done list choice, "done" label name | `ProjectBoardService.complete()/reopen()/completedList()`, `DONE_LABEL` |
| Board-membership 404 check | `ProjectBoardService.ensureList()/ensureCard()` |
| List move between boards | `ProjectBoardService.moveList()`, `TrelloApiClient.moveListToBoard()`, `ProjectController.moveList()` |
| Catch-all 500 / Trello WARN logging | `ProjectController.unexpected()/upstream()` |
| Snapshot patching after writes | `BoardSnapshotService.patchCard()/patchList()` |
| 429 mapping | `ProjectController.upstream()` |
| Ownership guard | `ProjectService.owned()`, `ProjectRepository.findByIdAndUserId` |
| Delete token TTL / format | `ProjectDeleteTokenService` |
| Upstream error mapping (409/502) | `ProjectController.upstream()` |
| Error status mapping | `@ExceptionHandler`s in `ProjectController` |
| Create/link behavior | `ProjectService.create()` |
| Import / board picker | `ProjectService.importBoards()/trelloBoards()`, `ProjectController` |
| Snapshot TTL, done/_meta rules, mapping | `BoardSnapshotService` |
| Snapshot shape / collection | `BoardSnapshot` (`trello_board_snapshots`) |
| List counts | `ProjectView.of()` |
| 412 Trello error mapping | `ProjectController.notConnected()/badTrelloToken()` |
| Runner link fields | `Project.runnerDeviceId/runnerProjectId/folderName/mapJobId`, `ProjectView.of()` |
| Link / unlink, reuse of an existing runner registration | `ProjectRunnerService.link()/register()/unlink()` |
| Map prompt | `src/main/resources/prompts/project-map.md` |
| Map job turn cap | `relay.map.max-turns` (default 200) |
| Map result parsing / limits | `ProjectRunnerService.parse()`, `MAX_SUBSYSTEMS` |
| Map storage shape / collection | `ProjectMap` (`project_maps`), `ProjectMapRepository` |
| Map summary fields | `ProjectRunnerService.mapSummary()` |
| Runner error → HTTP mapping | `ProjectRunnerController` `@ExceptionHandler`s |
| Planner endpoints / error mapping | `PlannerController` |
| Planner phases, busy rule, state derivation | `PlannerService.message()/lock()/ensureIdle()/state()` |
| Planner result storing (once) / parsing | `PlannerService.storeResult()/applyOnce()/extractObject()/parseDraft()` |
| Planner prompt text | `resources/prompts/planner-clarify.md`, `planner-draft.md` |
| Planner prompt size caps / turn cap | `relay.plan.map-max-chars`, `relay.plan.board-max-chars`, `relay.plan.history-max-chars`, `relay.plan.max-turns` |
| Which map notes are dropped first | `PlannerPrompts.mapSection()/relevance()` |
| Draft → Trello (list reuse, due fallback, meta block) | `PlannerService.apply()/withMeta()` |
| Double-apply guard (409 `planner_already_applied`) | `PlannerService.apply()` (`appliedAt` check), `PlannerController.alreadyApplied()`, `planner.js applyDraft()` |
| Conversation shape / collection | `PlannerConversation` (`planner_conversations`) |
