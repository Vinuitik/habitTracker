# Project Flows

Files: `Project.java`, `ProjectRepository.java`, `ProjectService.java`, `ProjectController.java`, `ProjectDeleteTokenService.java`, `ProjectView.java`, `BoardSnapshot.java`, `BoardSnapshotRepository.java`, `BoardSnapshotService.java`, `BoardSyncScheduler.java`, `ProjectBoardService.java`, `TrelloBoardGateway.java`, `HttpTrelloBoardGateway.java`, `StubTrelloBoardGateway.java` (test only). Direct Trello access: see `../integrations/trello/FLOWS.md`

A Project (name, description, userId, trelloBoardId, createdAt, imported) is 1:1 with a Trello board. Collection `projects`.

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

## Trello bridge (mongo-backup internal API)
`HttpTrelloBoardGateway` → `http://mongo-backup:8092` (no auth). Used ONLY for plan/apply now (board create/delete/read moved to the direct per-user `TrelloApiClient`; the agent still uses mongo-backup's global Trello account, so it only works on boards that account can reach).
To change URL: `trello.internal.base-url` (env `TRELLO_INTERNAL_BASE_URL`).

## Plan / apply (agent)
`POST /api/projects/{id}/plan {description}` → `ProjectController.plan()` → `ProjectService.plan()` (`owned()`, 404 if not owner; blank description or no board → 400) → `TrelloBoardGateway.plan()` → `POST /internal/agent/plan {boardId, description}` → `{cards, proposal}` returned as-is.
`POST /api/projects/{id}/apply {deadline?, pace?}` → `ProjectService.apply()` → `POST /internal/agent/apply` (only `deadline`/`pace` forwarded) → result as-is.
- Errors: upstream 409 (plan already running) → 409; any other upstream failure/timeout/unreachable → 502 (`TrelloGatewayException`). To change: `ProjectController.upstream()`.
- Timeouts: connect 5s; read 30s (apply), 6 min for plan. To change: `HttpTrelloBoardGateway` `READ_MS` / `PLAN_READ_MS`.

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
- Stub gateway is `@Profile("stub")`, activated only by `src/test/resources/application.properties`. Prod uses `HttpTrelloBoardGateway` (`@Profile("!stub")`).
- **No auth on the internal API** (deliberate): the only gate is that port 8092 is neither in the Caddyfile nor published. Anything else on the docker network (any container) can create/delete Trello boards and run the agent. Adding a published port or a Caddy route for it would expose that to the internet.
- No retries anywhere. Plan holds a servlet thread for up to ~5-6 min (fine at ~20 users; many concurrent plans would exhaust Tomcat threads). A javaapp-side timeout does not cancel the upstream run, so a retry may hit 409.
- Snapshot cache: 60s TTL, Mongo-backed (survives restart, shared across nodes), no invalidation besides `?refresh=true`; concurrent refreshes just upsert last-write-wins. Trello rate limit is ~100 req/10s per token (300/10s per key); each refresh is 1 request, import is 1 per board. No backoff: a 429 surfaces as 502 (or stale snapshot).
- Trello creds are per user (`trello_credentials`), apiKey+token stored **plaintext** in Mongo (not encrypted at rest, unlike `CapabilitySecret`). Token and key travel as query params to Trello (Trello's API design); never logged here.
- mongo-backup down → only plan/apply fail (502). Trello down → create/import/delete 502; board view falls back to stale snapshot. Delete token is consumed before the Trello call, so a failed delete needs a new token.
- Delete order: board first, Mongo row second, so an upstream failure leaves a retryable project; a board deleted upstream but a crash before `deleteById` leaves a project pointing at a missing board.
- Moving a list is two non-atomic steps for us (Trello move, then snapshot refetch of both boards). If the refetch fails the board view self-heals on the next refresh. Moving a list to a board the token can't write to yields a Trello 4xx surfaced as 502. Labels are board-scoped in Trello; moved cards keep their labels — verified 2026-10-27: Trello copies the labels onto the target board (same name/color, new board-scoped id).
- No unique constraint on project name per user.

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
| Trello bridge URL | `trello.internal.base-url` in `application.properties` |
| Upstream paths / timeouts (agent) | `HttpTrelloBoardGateway` |
| plan/apply endpoints | `ProjectController.plan()/apply()`, `ProjectService.plan()/apply()` |
| Upstream error mapping (409/502) | `ProjectController.upstream()`, `HttpTrelloBoardGateway.call()` |
| Test stub gateway | `StubTrelloBoardGateway`, `spring.profiles.active=stub` in test `application.properties` |
| Error status mapping | `@ExceptionHandler`s in `ProjectController` |
| Create/link behavior | `ProjectService.create()` |
| Import / board picker | `ProjectService.importBoards()/trelloBoards()`, `ProjectController` |
| Snapshot TTL, done/_meta rules, mapping | `BoardSnapshotService` |
| Snapshot shape / collection | `BoardSnapshot` (`trello_board_snapshots`) |
| List counts | `ProjectView.of()` |
| 412 Trello error mapping | `ProjectController.notConnected()/badTrelloToken()` |
