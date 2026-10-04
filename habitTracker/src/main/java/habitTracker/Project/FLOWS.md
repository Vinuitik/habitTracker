# Project Flows

Files: `Project.java`, `ProjectRepository.java`, `ProjectService.java`, `ProjectController.java`, `ProjectDeleteTokenService.java`, `TrelloBoardGateway.java`, `HttpTrelloBoardGateway.java`, `TrelloGatewayException.java`, `StubTrelloBoardGateway.java` (test only)

A Project (name, description, userId, trelloBoardId, createdAt) is 1:1 with a Trello board. Collection `projects`.

## CRUD + isolation
`/api/projects` → `ProjectController` → `ProjectService` → `SecurityUtils.getCurrentUserId()` → `ProjectRepository.findByUserId` / `findByIdAndUserId`
- By-id access never uses `findById`; another user's id yields 404, same as a missing one (no IDOR). To change: `ProjectService.owned()`.
- Create: blank `trelloBoardId` → `TrelloBoardGateway.createBoard(name)`; else `linkExisting(id)`. To change: `ProjectService.create()`.

## Two-step delete
`POST /api/projects/{id}/delete-request` → `ProjectService.requestDelete()` (ownership check) → `ProjectDeleteTokenService.issue()` → `{token}` (60s)
→ `DELETE /api/projects/{id}?token=` → `ProjectService.delete()` → `redeem(token, projectId, userId)` (atomic remove = single-use) → `TrelloBoardGateway.deleteBoard()` → `repository.deleteById()`
- Missing/wrong/expired/reused/cross-project token → 403. Board deleted before the Mongo row so a Trello failure leaves the project retryable.
To change TTL: `ProjectDeleteTokenService.DEFAULT_TTL_MS`.

## Trello bridge (mongo-backup internal API)
`HttpTrelloBoardGateway` → `http://mongo-backup:8092` (no auth). Java holds no Trello keys.
- `createBoard` → `POST /internal/boards {name}` → `{boardId}`; `deleteBoard` → `DELETE /internal/boards/{id}`; `linkExisting` just adopts the id (no upstream validation).
To change URL: `trello.internal.base-url` (env `TRELLO_INTERNAL_BASE_URL`).

## Plan / apply (agent)
`POST /api/projects/{id}/plan {description}` → `ProjectController.plan()` → `ProjectService.plan()` (`owned()`, 404 if not owner; blank description or no board → 400) → `TrelloBoardGateway.plan()` → `POST /internal/agent/plan {boardId, description}` → `{cards, proposal}` returned as-is.
`POST /api/projects/{id}/apply {deadline?, pace?}` → `ProjectService.apply()` → `POST /internal/agent/apply` (only `deadline`/`pace` forwarded) → result as-is.
- Errors: upstream 409 (plan already running) → 409; any other upstream failure/timeout/unreachable → 502 (`TrelloGatewayException`). To change: `ProjectController.upstream()`.
- Timeouts: connect 5s; read 30s (create/delete/apply), 6 min for plan. To change: `HttpTrelloBoardGateway` `READ_MS` / `PLAN_READ_MS`.

## Technology Notes
- Delete tokens are in-memory (`ConcurrentHashMap`): lost on restart, single-node only, expired entries are only purged when redeemed (small leak of unredeemed tokens until restart).
- A failed redeem still consumes the token (remove-first); the user must request a new one.
- Cross-user ids return 404 (not 403) deliberately, to avoid leaking existence.
- Stub gateway is `@Profile("stub")`, activated only by `src/test/resources/application.properties`. Prod uses `HttpTrelloBoardGateway` (`@Profile("!stub")`).
- **No auth on the internal API** (deliberate): the only gate is that port 8092 is neither in the Caddyfile nor published. Anything else on the docker network (any container) can create/delete Trello boards and run the agent. Adding a published port or a Caddy route for it would expose that to the internet.
- No retries anywhere. Plan holds a servlet thread for up to ~5-6 min (fine at ~20 users; many concurrent plans would exhaust Tomcat threads). A javaapp-side timeout does not cancel the upstream run, so a retry may hit 409.
- If mongo-backup is down/restarting: create → 502 (no project saved); plan/apply → 502; delete → 502 and the project is kept, but the delete token is already consumed (request a new one). Backup container is also the backup service, so a crash loop there blocks project writes.
- Delete order: board first, Mongo row second, so an upstream failure leaves a retryable project; a board deleted upstream but a crash before `deleteById` leaves a project pointing at a missing board.
- No unique constraint on project name per user.

## Change Index
| What | Where |
|---|---|
| Ownership guard | `ProjectService.owned()`, `ProjectRepository.findByIdAndUserId` |
| Delete token TTL / format | `ProjectDeleteTokenService` |
| Trello bridge URL | `trello.internal.base-url` in `application.properties` |
| Upstream paths / timeouts | `HttpTrelloBoardGateway` |
| plan/apply endpoints | `ProjectController.plan()/apply()`, `ProjectService.plan()/apply()` |
| Upstream error mapping (409/502) | `ProjectController.upstream()`, `HttpTrelloBoardGateway.call()` |
| Test stub gateway | `StubTrelloBoardGateway`, `spring.profiles.active=stub` in test `application.properties` |
| Error status mapping | `@ExceptionHandler`s in `ProjectController` |
| Create/link behavior | `ProjectService.create()` |
