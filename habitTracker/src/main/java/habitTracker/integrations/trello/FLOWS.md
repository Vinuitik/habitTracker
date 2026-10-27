# Trello Integration Flows

Files: TrelloCredential.java, TrelloCredentialRepository.java, TrelloCredentialController.java, TrelloApiClient.java, TrelloGatewayException.java, TrelloNotConnectedException.java, TrelloAuthException.java

Per-user Trello access (the user's own apiKey+token). Consumers: `Project/` (boards, mirror), `KPI/TrelloCardCountProvider` (card-count KPI).

## Credentials
`GET /api/integrations/trello/credentials` → `TrelloCredentialController.getStatus()` → `{configured}` (never returns secrets)
`PUT /api/integrations/trello/credentials {apiKey, token}` → `saveCredentials()` → upsert `trello_credentials` (unique `userId`)
- To change: `TrelloCredentialController`. Collection/REST path unchanged by the package move from `habitTracker.KPI`.

## Direct API calls
`TrelloApiClient.<method>(userId, ...)` → `TrelloCredentialRepository.findByUserId` → `RestTemplate` → `${trello.api.base-url}` (default https://api.trello.com; env `TRELLO_API_BASE_URL`)
- `listMyBoards` → `GET /1/members/me/boards?filter=open&fields=name,url,closed`
- `getBoard` → `GET /1/boards/{id}?fields=name,url,dateLastActivity&lists=all&list_fields=...&cards=all&card_fields=...` (one call, nested lists+cards, raw JSON `Map`)
- `getBoardActivity` → `GET /1/boards/{id}?fields=dateLastActivity` (cheap probe; returns the ISO string or null; 404 → `TrelloGatewayException` status 404). Consumers: `BoardSnapshotService.syncIfChanged()` (GET /board after TTL, `BoardSyncScheduler`).
- `createBoard` → `POST /1/boards?name=&defaultLists=false` → id; `deleteBoard` → `DELETE /1/boards/{id}`
- Write calls (params sent only if provided; built by `qs()`): `createList` `POST /1/lists` (idBoard,name,pos default bottom) · `updateList` `PUT /1/lists/{id}` · `getList` `GET /1/lists/{id}?fields=name,pos,closed,idBoard` · `createCard` `POST /1/cards` · `updateCard` `PUT /1/cards/{id}` (value `null` clears due) · `getCard` `GET /1/cards/{id}?fields=CARD_FIELDS` (same as board fetch, includes `idBoard`) · `getBoardLabels` `GET /1/boards/{id}/labels` · `createLabel` `POST /1/labels` · `addCardLabel` `POST /1/cards/{id}/idLabels?value=` · `removeCardLabel` `DELETE /1/cards/{id}/idLabels/{labelId}` · `moveListToBoard` `PUT /1/lists/{id}/idBoard?value={targetBoardId}&pos=` (list + its cards move; list id is kept). Consumer: `Project/ProjectBoardService`.
- Logging: both failure branches of `call()` log WARN `method path: RootCauseSimpleName: message` (never the URI, which holds key+token); `TrelloGatewayException` message for IO failures ends with `(RootCause: msg)`. To change: `TrelloApiClient.call()/rootCause()`.
- Errors: no/blank creds → `TrelloNotConnectedException`; Trello 401 → `TrelloAuthException`; other HTTP/IO → `TrelloGatewayException` (`status` = upstream code or 0). `ProjectController` maps the first two to 412.
- To change fields fetched: `TrelloApiClient.BOARD_QS`. Timeouts: connect 5s / read 15s (`CONNECT_MS`/`READ_MS`).

## Technology Notes
- apiKey+token are stored **plaintext** in Mongo (`trello_credentials`); anyone with DB access can use the user's Trello account. `CapabilitySecret` (KPI sandbox) is the encrypted alternative if this should change.
- Key/token go in the URL query (Trello's scheme): they can appear in proxy/access logs of anything between javaapp and api.trello.com; Spring exceptions may include the URI, so `TrelloApiClient` never puts `e.getMessage()` in its own messages.
- Rate limit ~100 req/10s per token (300/10s per key). No retries, no backoff; 429 becomes `TrelloGatewayException` (status 429, `retryAfter` from the Retry-After header); `ProjectController` returns 429 `trello_rate_limited` for it, other failures 502. Write calls are not idempotent-safe to retry (createCard would duplicate), hence no retries.
- `getBoard` with `cards=all` returns closed cards and closed lists too (filtered/flagged downstream). Very large boards => big payloads; the 15s read timeout is the only bound.
- Polling load: the background job (every 15 min) and open boards (60s) call the probe, 1 request each; the job spaces calls 200ms, and stops its run on a 429. Probes share the per-token 100 req/10s budget with user edits.
- List move: labels are board-scoped in Trello, but cards keep them — verified 2026-10-27: Trello copies the labels onto the target board (same name/color, new board-scoped id). `pos` on the `idBoard` endpoint is honoured (verified same day); `ProjectBoardService.moveList()` re-sends pos via `updateList` if the response pos differs.
- Hardcoded: Trello base URL default; if `trello.api.base-url` is wrong every call fails with 502.

## Change Index
| What | Where |
|---|---|
| Base URL | `trello.api.base-url` / `TRELLO_API_BASE_URL` |
| Endpoints, fields, timeouts | `TrelloApiClient` (`CARD_FIELDS`, `BOARD_QS`) |
| Write endpoints (lists/cards/labels) | `TrelloApiClient.createList()...removeCardLabel()` |
| Change probe | `TrelloApiClient.getBoardActivity()` |
| List move endpoint | `TrelloApiClient.moveListToBoard()` |
| Failure logging / root-cause text | `TrelloApiClient.call()`, `rootCause()` |
| Retry-After capture | `TrelloApiClient.call()`, `TrelloGatewayException.getRetryAfter()` |
| Credential storage / REST | `TrelloCredential*`, `TrelloCredentialController` |
| Error to HTTP mapping | `ProjectController` handlers |
