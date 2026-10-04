# Project Flows

Files: `Project.java`, `ProjectRepository.java`, `ProjectService.java`, `ProjectController.java`, `ProjectDeleteTokenService.java`, `TrelloBoardGateway.java`, `StubTrelloBoardGateway.java`

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

## Trello bridge [NOT IMPLEMENTED]
`StubTrelloBoardGateway` only logs and returns fake ids (`stub-<uuid>`). Java holds no Trello keys; the MCP has no create/delete board tools. To change: replace the `@Component` implementing `TrelloBoardGateway`.

## Technology Notes
- Delete tokens are in-memory (`ConcurrentHashMap`): lost on restart, single-node only, expired entries are only purged when redeemed (small leak of unredeemed tokens until restart).
- A failed redeem still consumes the token (remove-first); the user must request a new one.
- Cross-user ids return 404 (not 403) deliberately, to avoid leaking existence.
- Stub gateway: projects created now get fake board ids; real boards are untouched.
- No unique constraint on project name per user.

## Change Index
| What | Where |
|---|---|
| Ownership guard | `ProjectService.owned()`, `ProjectRepository.findByIdAndUserId` |
| Delete token TTL / format | `ProjectDeleteTokenService` |
| Trello bridge | implement `TrelloBoardGateway`, remove `StubTrelloBoardGateway` |
| Error status mapping | `@ExceptionHandler`s in `ProjectController` |
| Create/link behavior | `ProjectService.create()` |
