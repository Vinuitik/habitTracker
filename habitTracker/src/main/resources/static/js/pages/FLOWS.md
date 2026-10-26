# Projects page (frontend) FLOWS

Files: projects.html, js/pages/projects.js, js/organisms/board.js, js/organisms/card-modal.js, js/organisms/plan-drawer.js, js/molecules/card-tile.js, js/molecules/connect-panel.js, js/atoms/api.js, js/atoms/dom.js, js/atoms/menu.js, js/atoms/icons.js, js/env.js, styles/pages/projects.css

## Routing
`hashchange` → `route()` → fade out 180ms (`ENV.TIMING.VIEW_FADE_MS`) → `showIndex()` (`#/`) or `showBoard(id)` (`#/b/<id>`) → fade in.
Stale async results are dropped via the `seq` counter in `route()`.
To change routes: `route()` regex in js/pages/projects.js.

## Board index
`showIndex()` → `Api.request(PROJECTS)` → `renderIndex()` → tiles (`tileHtml()`) + dashed "Create new board" tile.
Tile click → `location.hash = #/b/<id>`; kebab → `Menu.open()` → Delete → two-step delete.
To change tile content: `tileHtml()`; tile CSS: styles/molecules/board-tile.css.

## Import
"Import from Trello" → `openImport()` → GET `PROJECTS_TRELLO_BOARDS` → checklist (imported = disabled) → POST `PROJECTS_IMPORT {trelloBoardIds}` → `refreshIndex()`.

## Board view
`showBoard()` → `Api.raw(PROJECT_BOARD(id, refresh))` (reads `X-Stale`) → `Board.render()` → columns (lists by pos, closed skipped) → `CardTile.html()` per card.
Refresh button → `showBoard(id, seq, true)` (`?refresh=true`).
Card click → `CardModal.open()` (read-only; strips ```meta blocks from desc; escapes everything).
"Plan with AI" → `PlanDrawer.toggle(id)` → POST `PROJECT_PLAN` → (optional) POST `PROJECT_APPLY` → `onChange` re-fetches board with refresh=true.
To change label colors: `CardTile.tagClass()`; due thresholds: `CardTile.dueInfo()` (48h warning).

## Trello not connected (HTTP 412)
Any index/board/import/create call → `Api.isTrelloAuth(err)` → `showConnect()` → `ConnectPanel.render()` → PUT `TRELLO_CREDENTIALS {apiKey, token}` → `route()` (retry).
`trello_token_invalid` changes only the panel copy.

## Delete (two-step)
`startDelete()` → POST `PROJECT_DELETE_REQUEST` (token + countdown) → type name → DELETE `PROJECT_DELETE(id, token)`.
Copy differs for `project.imported` (unlink vs delete Trello board): `startDelete()`.

## Change Index
| Thing | Where |
|---|---|
| Endpoint URLs / Trello help URL / fade ms | js/env.js (`ENDPOINTS`, `EXTERNAL`, `TIMING`) |
| List column width | `--list-width` in styles/tokens.css |
| Label/due styling | CardTile.tagClass / dueInfo, styles/atoms/badge.css |
| Card detail layout | CardModal.open, `.modal-box--wide` in styles/molecules/modal.css |
| Drawer / bottom sheet | styles/organisms/drawer.css |
| Mobile snap columns | styles/molecules/list-column.css, organisms/board.css (≤768px) |

## Technology Notes
- No client state persistence: every route change refetches; no localStorage. Reload on a board URL works (hash is the state).
- Hash routing: the service worker only sees `/projects`; board URLs are not separately cacheable offline. `/api/projects` is not in sw.js API_PREFIXES, so project data is never cached offline.
- Plain scripts + window globals; load order in projects.html matters (atoms → molecules → organisms → pages).
- `Api.request` has no timeout; `/plan` can take minutes (UI just shows "Drafting…").
- Trello strings are escaped with `Dom.esc`; hrefs allow only `https://` (`Dom.safeUrl`).
- Board height uses `100dvh` minus top/tab bars; older browsers without `dvh` fall back poorly.
- Progress bar uses `<progress>` (avoids inline width styles).
- Read-only this stage: no card moves/edits; Refresh is the only way to see Trello-side changes.
