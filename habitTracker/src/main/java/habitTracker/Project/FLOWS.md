# Project Flows

Files: `Project.java`, `ProjectRepository.java`, `ProjectService.java`, `ProjectController.java`, `ProjectDeleteTokenService.java`, `ProjectView.java`, `BoardSnapshot.java`, `BoardSnapshotRepository.java`, `BoardSnapshotService.java`, `TrelloBoardGateway.java`, `HttpTrelloBoardGateway.java`, `StubTrelloBoardGateway.java` (test only). Direct Trello access: see `../integrations/trello/FLOWS.md`

A Project (name, description, userId, trelloBoardId, createdAt, imported) is 1:1 with a Trello board. Collection `projects`.

## CRUD + isolation
`/api/projects` → `ProjectController` → `ProjectService` → `SecurityUtils.getCurrentUserId()` → `ProjectRepository.findByUserId` / `findByIdAndUserId`
- By-id access never uses `findById`; another user's id yields 404, same as a missing one (no IDOR). To change: `ProjectService.owned()`.
- List: `ProjectService.list()` → `ProjectView` = Project fields + `cardCount`/`doneCount` (non-closed cards of the snapshot) + `syncedAt`; null if never synced. To change: `ProjectView.of()`.
- Create: blank `trelloBoardId` → `TrelloApiClient.createBoard(userId, name)` (user's own Trello, no snapshot yet); else `TrelloApiClient.getBoard()` validates → `imported=true` + snapshot stored. To change: `ProjectService.create()`.

## Import + board mirror
`GET /api/projects/trello/boards` → `ProjectService.trelloBoards()` → `TrelloApiClient.listMyBoards()` → `[{id,name,url,imported}]` (imported = user already has a project with that boardId)
`POST /api/projects/import {trelloBoardIds}` → `ProjectService.importBoards()` → skip ids already linked/duplicated → `getBoard()` → `Project(imported=true, name=description=board name)` → `BoardSnapshotService.store()` → `[Project]` (201)
`GET /api/projects/{id}/board?refresh=false` → `ProjectService.board()` (`owned()`) → `BoardSnapshotService.get()` → fresh (<60s) cached snapshot, else `TrelloApiClient.getBoard()` → map → upsert `trello_board_snapshots` → `{projectId,boardId,name,url,syncedAt,lists,cards}`
- Trello failure with an existing snapshot → snapshot returned with `X-Stale: true`; without → 502.
- Mapping: list `_meta` and its cards dropped; card `done` = label named "done" (ci) OR list named Done/Completed (ci). To change: `BoardSnapshotService.map()`, TTL `BoardSnapshotService.TTL_SECONDS`.
- Errors: no creds → 412 `{error:"trello_not_connected"}`; Trello 401 → 412 `{error:"trello_token_invalid"}`. To change: `ProjectController` handlers.

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
- No unique constraint on project name per user.

## Change Index
| What | Where |
|---|---|
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
