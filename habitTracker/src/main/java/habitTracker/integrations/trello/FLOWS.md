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
- `getBoard` → `GET /1/boards/{id}?fields=name,url&lists=all&list_fields=...&cards=all&card_fields=...` (one call, nested lists+cards, raw JSON `Map`)
- `createBoard` → `POST /1/boards?name=&defaultLists=false` → id; `deleteBoard` → `DELETE /1/boards/{id}`
- Errors: no/blank creds → `TrelloNotConnectedException`; Trello 401 → `TrelloAuthException`; other HTTP/IO → `TrelloGatewayException` (`status` = upstream code or 0). `ProjectController` maps the first two to 412.
- To change fields fetched: `TrelloApiClient.BOARD_QS`. Timeouts: connect 5s / read 15s (`CONNECT_MS`/`READ_MS`).

## Technology Notes
- apiKey+token are stored **plaintext** in Mongo (`trello_credentials`); anyone with DB access can use the user's Trello account. `CapabilitySecret` (KPI sandbox) is the encrypted alternative if this should change.
- Key/token go in the URL query (Trello's scheme): they can appear in proxy/access logs of anything between javaapp and api.trello.com; Spring exceptions may include the URI, so `TrelloApiClient` never puts `e.getMessage()` in its own messages.
- Rate limit ~100 req/10s per token (300/10s per key). No retries, no backoff; 429 becomes `TrelloGatewayException` (502 to the client).
- `getBoard` with `cards=all` returns closed cards and closed lists too (filtered/flagged downstream). Very large boards => big payloads; the 15s read timeout is the only bound.
- Hardcoded: Trello base URL default; if `trello.api.base-url` is wrong every call fails with 502.

## Change Index
| What | Where |
|---|---|
| Base URL | `trello.api.base-url` / `TRELLO_API_BASE_URL` |
| Endpoints, fields, timeouts | `TrelloApiClient` |
| Credential storage / REST | `TrelloCredential*`, `TrelloCredentialController` |
| Error to HTTP mapping | `ProjectController` handlers |
