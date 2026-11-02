# Projects page (frontend) FLOWS

Files: projects.html, js/pages/projects.js, js/organisms/board.js, js/organisms/merge-view.js, styles/organisms/merge-view.css, js/organisms/card-modal.js, js/organisms/planner.js, js/organisms/planner-mock.js, styles/organisms/planner.css, js/molecules/card-tile.js, js/molecules/composer.js, js/molecules/connect-panel.js, js/molecules/folder-picker.js, js/molecules/step-list.js, styles/molecules/step-list.css, js/organisms/repo-link.js, js/atoms/relay-api.js, styles/molecules/folder-picker.css, styles/organisms/repo-bar.css, js/atoms/dnd.js, js/atoms/api.js, js/atoms/dom.js, js/atoms/menu.js, js/atoms/icons.js, js/env.js, styles/pages/projects.css

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
Card click → `CardModal.open()` (editable, see Card writes).
"Plan with AI" → `Planner.open(id, btn)` (full-screen, see Planner below).
To change label colors: `CardTile.tagClass()`; due thresholds: `CardTile.dueInfo()` (48h warning).

## Board writes (optimistic)
All in `Board` (js/organisms/board.js); state `S.data` is the single source, DOM is refreshed per list via `refreshList()`.
Pattern: `mutate(card, localFields, call)` → apply local fields + `refreshLists()` → API call → success: `Object.assign(card, serverCard)` (only when no other write to that card is in flight, `pend` map) → failure: restore only the touched fields, refresh, `fail()` toast.
`fail()`: 412 → `h.onAuth` (connect panel); 429/`trello_rate_limited` → "Trello is rate limiting, try again in a moment"; else server message.
- Add card: list footer `Composer` → `addCard()` → temp card `tmp-N` (dimmed, not clickable/draggable) → POST `PROJECT_CARDS {idList,name,pos}` → temp object merged with server card; failure removes it. Enter keeps composer open.
- Add list: trailing dashed column `Composer` → `addList()` → temp column → POST `PROJECT_LISTS {name,pos}`; failure removes column.
- Rename list: kebab (`listMenu()`) → `startRename()` inline input (Enter/blur saves, Esc cancels) → `renameList()` → PATCH `PROJECT_LIST {name}`.
- Archive list: kebab → inline confirm inside `Menu` (`confirm:` item option) → `archiveList()` → PATCH `{closed:true}`; Undo toast PATCHes `{closed:false}`.
- Drag card: `Dnd.init` (pointer events) → `Board.onDrop()` → `posBetween(prev,next)` → `mutate` → PATCH `{idList,pos}`.
  pos math: both neighbours = (prev.pos+next.pos)/2; top = next.pos/2; bottom = prev.pos + 65536 (`ENV.BOARD.POS_STEP`); empty list = 65536. Dropping where it already sits is a no-op.
  Mouse: drag after 5px move. Touch/pen: only after 250ms hold with <8px movement (else the browser scrolls normally). Ghost follows pointer, dashed placeholder shows the slot, rAF loop auto-scrolls the board (horizontal) and the hovered list (vertical) within 48px of an edge; Esc cancels; the click after a drag is swallowed.
- Reorder lists: drag the list header (`Dnd.init` second instance, `axis:'x'`, `handleSel:'.list-column__header'`; touch = same 250ms long-press) → `Board.onListDrop()` → `posBetween(prev,next)` → optimistic `list.pos` + `renderColumns()` → PATCH `PROJECT_LIST {pos}`; failure restores pos.
- Quick complete: hover check button on tile (`data-act="quick"`, hover-capable devices only) → `completeCard()`/`reopenCard()` → POST `.../complete` or `.../reopen`; server may move the card (e.g. to Completed), `mutate` refreshes old + new list.
- Planner "Add to board" → `Planner` onChange → `showBoard(id, seq, true)` re-fetch (replaces all local state).
To change optimistic behaviour: `mutate()`; list pos step / drag thresholds: `ENV.BOARD`; long-press ms / undo toast ms: `ENV.TIMING`.

## Merge boards
Board header "Merge into…" → `h.pickBoard()` (projects.js `pickBoard()`: GET `PROJECTS`, `Menu` of the other projects) → `h.onMerge(target)` = `startMerge()` → `stopPoll()` + parallel GET `PROJECT_BOARD` for both → `MergeView.open()` (replaces the board view): source pane left (top on ≤768px), target pane right (bottom), each a `.board__scroller`.
- Drag a list header within or across panes (`Dnd.init`, `axis:'x'`, drop slot = placeholder between columns). Same pane → PATCH list pos. Other pane → `moveList()`: optimistic remove (list + its cards) / insert, POST `PROJECT_LIST_MOVE {targetProjectId,pos}`, success replaces the destination side with the returned snapshot, failure restores both sides + `Board.fail()` toast. Per-list kebab "Move to “<other board>”" is the non-drag fallback; the normal board's list kebab "Move list to board…" → `Board.moveListToBoard()` (same endpoint, pos bottom).
- Source has no open lists → banner "Everything moved. Delete this board?" → `h.onDeleteSource()` → `startDelete()` (imported = unlink copy, app-created = deletes the Trello board). "Exit merge" → `showBoard(id, seq, true)` (normal view, poll restarts).
To change: `MergeView.moveList()/onDrop()`, `startMerge()`, `pickBoard()`.

## Planner (Plan with AI)
`Planner.open(id)` → #planner (full-screen dialog, focus trapped, body gets `.planner-open`).
Brief → `startJob('chat')` → POST `PLANNER_START {message, conversationId?}` → `{conversationId, jobId}` → `poll()` every `ENV.TIMING.PLANNER_POLL_MS` → GET `PLANNER_POLL(id, conv, after=nextSeq)` → `ingest()` → `renderAll()`; polling stops when `state` ≠ running.
- `waiting_user` + `questions` → one textarea per question (`renderComposer()`, rebuilt only when questions change so typed text survives), answers sent as one message; "Lock decisions & draft" → POST `PLANNER_LOCK` → running again.
- Work steps: events of the current job render as rows under the triggering message (`jobHtml()` → `StepList.html()`): last step while running = ● + elapsed seconds (1s ticker `StepList.tick()`, no looping animation), earlier = ✓; `text` = ·, `error` = ✕. `StepList` (js/molecules/step-list.js + styles/molecules/step-list.css, classes `.step-list` / `.step-row--*`) is shared with the KPI Automate dialog (js/kpi-list.js, see `KPI/FLOWS.md`).
- Draft: adopted from every poll while running (read-only), final on `drafted` (`adoptDraft()` gives each card a key `k`; schedule entries link by title → `k`). Edits (rename / delete / importance pips) mutate `S.draft` and the linked schedule entries; week strip re-renders (`renderWeek()`: one column per day first→last date or deadline, cap 62 days, blocks sized by importance).
- "Add to board" (only Trello write) → POST `PLANNER_APPLY {draft, deadline?, pace?}` → `{created}` → toast → `discard()` → onChange.
- Close / Esc → `requestClose()`: conversation or draft present → in-dialog confirm (Keep editing / Discard); else discard. Route change → `Planner.hide()` keeps state (reopening the same project resumes; another project discards it).
- 409/503 `{error:"runner_offline"}` → `.planner__offline` banner linking `ENV.ROUTES.INSTALL`; the failed turn is rolled back, text stays in the field.
- <640px (`ENV.PLANNER.MOBILE_QUERY` + CSS): Conversation / Draft tabs (`setTab()`), dot on Draft when a draft arrives, auto-switch to Draft on `drafted`.
- Dev mock: `ENV.FEATURES.PLANNER_MOCK = true` → `api()` uses `PlannerMock` (js/organisms/planner-mock.js): timed fake steps, 3 questions, draft growing list by list; a brief containing "offline" fakes runner_offline.
To change: endpoints `ENV.ENDPOINTS.PLANNER_*`; poll ms `ENV.TIMING.PLANNER_POLL_MS`; default pace `ENV.PLANNER.DEFAULT_PACE`; block sizes `.week-strip__block--i1..3` in styles/organisms/planner.css.

## Card modal (editable)
`CardModal.open(card)` → `render()` from `Board.getCard(id)`; every control → `act(fn)` = optimistic Board action, render now, render again after the server answer/rollback.
- Title: input, blur/Enter saves (`saveTitle()`), Esc reverts → PATCH `{name}`.
- Description: textarea + Save/Cancel (shown when dirty). ```meta blocks are stripped from the editor (`stripMeta`) and re-appended unchanged on save (`metaBlocks`). Unsaved text survives re-renders (`descDraft`).
- Due: date + optional time (default `ENV.BOARD.DEFAULT_DUE_TIME`, local → ISO via `toISOString()`); Clear → `{due:null,dueComplete:false}`.
- Move to…: select of open lists → `Board.moveCardToList()` → PATCH `{idList,pos:"bottom"}` (non-drag move).
- Mark complete / Reopen (by `card.done`) → POST complete/reopen. Archive → PATCH `{closed:true}`, modal closes, Undo toast PATCHes `{closed:false}`.
Mobile: action buttons sit in one row directly under the title (`.card-detail__actions`).

## Live sync (Trello-side edits)
`showBoard()` → `startPoll(id, my)` → every `ENV.TIMING.BOARD_POLL_MS` (60s), plus `window` focus / `visibilitychange` → `pollBoard()` → skipped unless `document.visibilityState==='visible'` and `!Board.isBusy()` (drag `dnd-active`, in-flight write, open composer / inline list rename, `CardModal.isDirty()`) → `Api.raw(PROJECT_BOARD(id,false))` → result dropped if route changed, or `Board.stamp()` moved / `isBusy()` became true during the request → `Board.apply(data, stale)` → `CardModal.onRemote(changedIds)`.
- `Board.apply()`: diffs lists (`listSig`) and cards (`cardSig`); open-list set/order/name change → `renderColumns()` (keeps horizontal + per-column scroll); else `refreshLists()` only for lists whose cards changed. Header name/synced/stale badge updated in place.
- Open card modal: changed remotely and not dirty → `render()`; deleted/archived → `close()` + toast. Dirty modal skips the whole tick.
- `route()` → `stopPoll()`. 412 during poll → `showConnect()`; other errors silent.
- `boardMissing` in the response → `Board.setMissing()` shows the header banner "This board no longer exists in Trello" + Unlink → `h.onUnlink` → `unlinkMissing()` → `startDelete()` (normal two-step; navigates to `#/` afterwards).
To change: poll interval `ENV.TIMING.BOARD_POLL_MS`; busy rules `Board.isBusy()`; diff fields `cardSig()`.

## Repo link + repo map (board header row)
`showBoard()` → `Board.render()` (header has an empty `[data-repo]` slot) → `RepoLink.mount(slot, id)` → GET `PROJECT(id)` → `project.link` (`{deviceId, path}`) decides the row.
- Not linked: "Link folder" → `FolderPicker.open(id, onLinked)` (modal `#folder-modal`):
  1. Runner: GET `RELAY_DEVICES` → rows (online first; offline disabled with "last seen"). None → empty state + "Install a runner" → `ROUTES.RELAY_INSTALL` (`/relay#/install`).
  2. Browse: GET `RELAY_BROWSE(dev, path)` starting at `ENV.REPO.PICKER_START` (`~` = Documents/home); `''` = filesystem roots ("Computer" crumb). Breadcrumb from `BrowseResult.path` (Linux `/` or Windows `C:\` paths), `..` row = `parent`, folder rows are buttons. Keys: ↑/↓ move, Enter/→ open, ←/Backspace up, Esc close.
  3. "Select this folder" (disabled in the roots view) → POST `PROJECT_LINK {deviceId, path}` → updated project → modal closes → row re-renders + map status loads.
- Linked: "Linked to <folder>" (full path in tooltip) + Unlink (Menu inline confirm) → DELETE `PROJECT_LINK` (also drops any job state for that project).
- Map status: GET `PROJECT_MAP` → `{commitSha, builtAt, subsystems[]}` = "Mapped · sha7 · age · N subsystems"; 404 = "Not mapped".
- "Map repo"/"Remap" (note: uses your Claude tokens) → POST `PROJECT_MAP` → `{jobId}` → `tick()` every `ENV.TIMING.MAP_POLL_MS` (2s): GET `PROJECT_MAP_JOB(id, jobId, after)` → append `events[]` (`{kind: step|text|error, text}`), `after = nextSeq` → `state|status` `done` → panel closes, toast, status refetched; `failed` → panel stays with `error` (or the last error event) + Close. 404 on the job or 5 consecutive poll errors = failed.
- Hub errors via `RelayApi.call()`: 401 → `/2fa/verify?next=<current url>`; 503 → "Runner offline"; 504 → "Runner didn't answer".
To change: picker steps `FolderPicker.showDevices()/browse()/renderBrowse()`; header row `RepoLink.render()/renderProgress()`; poll `RepoLink.tick()`; error mapping `RelayApi.call()`.

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
| Planner layout / week strip | styles/organisms/planner.css |
| AI work-step rows (planner + KPI Automate) | `StepList` (js/molecules/step-list.js), styles/molecules/step-list.css |
| Planner flow / API adapter / mock toggle | `Planner` (js/organisms/planner.js), `ENV.FEATURES.PLANNER_MOCK`, `ENV.ENDPOINTS.PLANNER_*` |
| Write endpoints | js/env.js (`PROJECT_LISTS/LIST/CARDS/CARD/CARD_COMPLETE/CARD_REOPEN`) |
| Optimistic write / rollback | Board.mutate, Board.fail |
| Drop position math | Board.posBetween, `ENV.BOARD.POS_STEP` |
| Drag thresholds, autoscroll, long-press | `ENV.BOARD`, `ENV.TIMING.DRAG_HOLD_MS`, js/atoms/dnd.js |
| Drag ghost / placeholder look | styles/atoms/dnd.css |
| Composer (add card/list) | js/molecules/composer.js, styles/molecules/composer.css |
| List kebab items / inline confirm | Board.listMenu, Menu `confirm` option |
| Merge view layout / split | styles/organisms/merge-view.css, `MergeView.open()` |
| List reorder / list move | `Board.onListDrop()/moveListToBoard()`, `MergeView.reorder()/moveList()`, `ENDPOINTS.PROJECT_LIST_MOVE` |
| Quick-complete button | CardTile.html, styles/molecules/card-tile.css |
| Card modal fields | CardModal.render / onClick / onChange |
| Undo toast | Toast.show(msg, ms, {label,onClick}), `ENV.TIMING.UNDO_TOAST_MS` |
| Poll interval | `ENV.TIMING.BOARD_POLL_MS` |
| Poll gating / apply | projects.js `pollBoard()`, `Board.isBusy()/stamp()/apply()` |
| Open-modal remote refresh / dirty check | `CardModal.onRemote()/isDirty()` |
| Missing-board banner + Unlink | `Board.setMissing()`, `.board__banner` in styles/organisms/board.css, projects.js `unlinkMissing()` |
| Repo link / map URLs, picker start dir, poll ms, log cap | js/env.js (`PROJECT_LINK/MAP/MAP_JOB`, `RELAY_DEVICES/BROWSE`, `ROUTES.RELAY_INSTALL/TWOFA_VERIFY`, `REPO`, `TIMING.MAP_POLL_MS`) |
| Folder picker steps / keyboard | `FolderPicker` (js/molecules/folder-picker.js), styles/molecules/folder-picker.css |
| Repo row / map progress panel | `RepoLink.render()/renderProgress()/tick()`, styles/organisms/repo-bar.css |
| Hub 401/503/504 handling | `RelayApi.call()` (js/atoms/relay-api.js) |
| Mobile snap columns | styles/molecules/list-column.css, organisms/board.css (≤768px) |

## Technology Notes
- No client state persistence: every route change refetches; no localStorage. Reload on a board URL works (hash is the state).
- Hash routing: the service worker only sees `/projects`; board URLs are not separately cacheable offline. `/api/projects` is not in sw.js API_PREFIXES, so project data is never cached offline.
- Plain scripts + window globals; load order in projects.html matters (atoms → molecules → organisms → pages).
- `Api.request` has no timeout. Planner progress is pull-based (poll every 1.2s while running, exponential backoff, gives up after 5 consecutive errors → `failed`); no SSE/websocket.
- Planner state is in memory only: a page reload loses the conversation and any unsaved draft (server keeps the conversation, but the client has no "resume by id" [NOT IMPLEMENTED]).
- Planner schedule entries are linked to cards by title at adoption; two cards with the same title share the first card's link.
- Planner backend endpoints (`/api/projects/{id}/planner…`) are [NOT IMPLEMENTED] as of this frontend; use the mock flag to demo.
- Trello strings are escaped with `Dom.esc`; hrefs allow only `https://` (`Dom.safeUrl`).
- Board height uses `100dvh` minus top/tab bars; older browsers without `dvh` fall back poorly.
- Progress bar uses `<progress>` (avoids inline width styles).
- Trello-side edits arrive via 60s polling while the board is open and visible (server side: 15-min background sync + `dateLastActivity` probe), so latency is up to ~1-2 min; webhooks/push [NOT IMPLEMENTED]. Polling pauses during writes/drags/edits, so a long-open composer or dirty modal defers updates. Optimistic writes can still overwrite newer Trello-side values (last write wins). Each poll is one GET (a Trello probe only after the 60s server TTL); background tabs don't poll, and a poll runs on return to the tab.
- Temp ids (`tmp-N`) exist only client-side until the POST returns; those cards/lists reject drag, menu and add-card actions.
- `pos` is a float; repeated midpoint drops between the same two cards eventually exhaust double precision (~50 halvings); Trello rebalances server-side but the client does not.
- Touch drag relies on a non-passive `touchmove` listener on the board (iOS ignores preventDefault from listeners added mid-gesture); `touch-action` stays pan-x/pan-y so scrolling works before the long-press fires.
- Rollback restores only the fields a failed write touched; an in-flight second write on the same card defers applying the server response until both finish.
- Merge view is a separate lightweight renderer (read-only cards, no composer/modal); it holds its own copy of both boards and the 60s poll is stopped while it is open. The rollback of a failed cross-board move re-inserts only that list and its cards, so concurrent moves don't clobber each other. Labels are board-scoped in Trello: cards moved across keep their labels — verified 2026-10-27: Trello copies the labels onto the target board (same name/color, new board-scoped id).
- A list drag needs the pointer on the header (not the kebab/name input); a dragged tmp list (still being created) is rejected with a toast.
- Hover quick-complete is hidden on touch devices (`@media (hover: hover)`); use the modal there.
- Repo link: the link fields are read from `project.runnerDeviceId + folderName` (GET `/api/projects/{id}`); if the backend names them differently, only `RepoLink.linkOf()` changes.
- Map job state lives in memory per page load (`RepoLink` `jobs` map, keyed by project): it survives board re-renders and navigating between boards, but a page reload loses the jobId, so a running job's progress is no longer shown (the job itself keeps running server-side; the status updates once it lands). Hidden tabs skip job polls.
- Folder browsing is unscoped on the runner (any directory, dotfolders hidden) — that's the runner's `/v1/browse` contract; every call needs a verified 2FA session (`/relay/api/**`).
