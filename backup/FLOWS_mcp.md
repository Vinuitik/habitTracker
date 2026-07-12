# Trello MCP Server Flow

Files: `mcp_server.py`, `tests/test_mcp_server.py`

## Overall Architecture

```
You ──► Claude / ChatGPT (with MCP connected)
           │
           ▼
  MCP endpoint (HTTPS)  habittrackerdima.me/mcp/...
           │
     Caddy strips /mcp
           │
           ▼
  mongo-backup:8091 (FastMCP streamable-http)
           │
           ▼
     Trello REST API   ← single source of truth

Background thread (every 1h) ──► labels overdue / due-today cards
```

## Ingress Chain

```
Cloudflare → cloudflared → Caddy:80 → handle_path /mcp/* → mongo-backup:8091
```

Caddy `handle_path /mcp/*` strips the `/mcp` prefix before proxying.
To change route prefix: `caddy/Caddyfile` handle_path directive + MCP client config URL.
To change port: `mcp_server.py` `mcp.run(port=...)` + `caddy/Caddyfile` proxy target.

---

## Process Model

`start.sh` runs two processes in the `mongo-backup` container:

| Process | How | Role |
|---|---|---|
| `backup.py` | background (`&`) | MongoDB → Google Drive every 12h |
| `mcp_server.py` | foreground (`exec`) | FastMCP server + hourly cron thread |

If `mcp_server.py` exits, the container stops (PID 1 via `exec`). `backup.py` failures are silent.

---

## Core idea: handles, not IDs (stateless)

A **handle** is `board/list/card` in kebab-case — `frm/auth/google-sso`. Every tool that used
to take a Trello ID now takes a handle or a plain name; Trello IDs never leave the server.

**No persistent store.** Handles are derived from live Trello names on every call and resolved
back to IDs server-side. Trello is the only source of truth, so there is nothing to sync and
nothing to invalidate. See `## Technology Notes` for why we chose this over a slug↔id mirror.

Resolution chain (all in-memory, cached only for the duration of one tool call):

```
name/handle ──► _resolve_board() ──► _resolve_list() ──► _resolve_handle() ──► Trello id
                 (slug match on           (slug match          (slug match on card name,
                  /members/me/boards)      on board.lists)      ~id4 tiebreak on collision)
```

- `_slug()` — `"Google SSO"` → `google-sso`. To change handle format: `_slug()` + `_build_handles()`.
- `_build_handles()` — assigns a handle per card; appends `~<first4ofid>` **only** when two cards
  in the same list slug-collide. Deterministic, so a handle is stable across calls without a store.
- Miss → `ToolError` with `difflib` near-matches ("did you mean …?"). To change fuzziness: the
  `difflib.get_close_matches(...)` calls in `_resolve_board` / `_resolve_list` / `_resolve_handle`.

---

## Tool Surface (6 tools)

All reads omit-empty (`_clean()` drops `None`/`""`/`[]`/`{}`, keeps `0`/`False`).

### describe_board(board)
`_resolve_board` → `_cards` → `Counter(idList)`.
Returns `{board, slug, lists:[{list,cards}], total_cards}`. Cheap situational awareness — call
first instead of dumping every card.

### get_cards(board, list_name?, label?, text?, due_before?, has_due?, limit=50)
`_resolve_board` → `_cards(checklists=True)` → apply each filter only if passed (AND) → `_build_handles`.
Returns **compact tab-delimited lines**, not JSON:
```
handle <TAB> title <TAB> list [<TAB> due:YYYY-MM-DD] [<TAB> [label,label]] [<TAB> check:done/total]
```
Empty meta tokens are omitted. `"No cards match."` when the filtered set is empty.
- `due_before` / due compare on the `YYYY-MM-DD` prefix (lexicographic, ISO-safe).
- Checklists are summarised to `check:done/total` here; full items only via `get_card`.
To change columns/format: the line-building loop at the end of `get_cards`.

### get_card(handle)
`_resolve_handle(checklists=True)` → recompute canonical handle over all board cards → expand checklist.
Returns full detail incl. `checklist:[{name,done}]`, omit-empty.

### create_cards([NewCard]) — batch
Per item: `_resolve_board` → `_resolve_list` → `_label_ids` → `POST /cards` → optional `_write_checklist`.
`NewCard{board, list_name, title, description="", labels=[], due=None, checklist=[]}`.
Returns `{created:[{handle,url}], errors?:[{index,title,error}]}`. One bad item doesn't abort the batch.

### update_cards([CardUpdate]) — batch, partial
Per item: `_resolve_handle` → only set fields are sent.
`CardUpdate{handle, title?, description?, labels?, due?, checklist?}`.
- `title`/`description`/`due` batched into one `PUT /cards/{id}` (guarded by `len(scalar) > 2`).
- `labels` → `_label_ids` → separate `PUT` (replaces all).
- `checklist` → delete existing checklists → `_write_checklist` (replaces).
- `due="null"` (string) clears; `due=None` (default) leaves unchanged.
Returns `{updated:[{handle,applied}], errors?}`.

### move_cards([CardMove]) — batch
Per item: `_resolve_handle` → `_resolve_list(to_list)` → `PUT /cards/{id}` with `idList`+`pos`.
`CardMove{handle, to_list, pos="bottom"}`. Returns `{moved:[{from,to}], errors?}` (`to` is the new handle).

To change the checklist name: `_write_checklist` → `POST /checklists name=Tasks`.
Unknown label names are silently skipped (`_label_ids` keeps only slugs present on the board).

---

## Cron: Hourly Card Status Labels

`_cron_loop()` (daemon thread, `sleep(3600)`) → `_cron_update_card_statuses()`.
Requires `TRELLO_CRON_BOARD_ID` (or resolves `TRELLO_CRON_BOARD_NAME`); skips silently otherwise.

| Condition (cards with a due date) | Action |
|---|---|
| `due < now` | add `overdue`, remove `due-today` |
| `due.date() == today` | add `due-today`, remove `overdue` |
| no due date | skipped |

Labels `overdue` / `due-today` must exist on the board. Unchanged label sets → no PUT.
**Note:** nothing in the tool read-path consumes these labels — they exist purely for the *visual*
Trello board. If you only ever touch the board through the agent, this cron is optional dead weight
(and deleting it removes the only process that writes to Trello behind the tools' back).
To change interval: `time.sleep(3600)`. To extend logic: `_cron_update_card_statuses()`.

---

## Technology Notes

**Stateless / no slug↔id store — the core architectural decision.**
A persistent slug↔id mirror would buy rename-stable handles and "what changed since last sync"
diffs, but it introduces cache invalidation: the mirror drifts whenever Trello is edited from the
web UI, mobile, *or this server's own hourly cron*. At this scale (one agent, one personal board,
~tens of cards) that cost isn't worth it, so **Trello stays the sole source of truth** and handles
are recomputed live each call. Consequences to know:
- **Renaming a card changes its handle** (slug is derived from the current title). Fine here because
  the agent re-reads the board each session anyway; would not be fine in a multi-user product.
- **Collision handles (`~id4`) are only stable while the colliding cards exist.** Deterministic given
  the card ids, but if you delete one of two "Deploy" cards, the survivor's base handle stops colliding.
- **Every call hits Trello** (no cache). One board fetch per tool call (~100–300ms). Acceptable at
  ~20 cards / 1 agent; would need Redis + TTL, not in-process dicts, to scale horizontally.
- **Per-call caches are plain dicts**, discarded when the tool returns — correct for a single process,
  structurally wrong for N app instances (each would hold a divergent copy).

**FastMCP 3.x.** `requirements.txt` pins `fastmcp` unpinned → the image builds against latest 3.x.
In v3 `@mcp.tool()` returns the original coroutine (and registers it as a side effect); there is no
`.fn` wrapper, so tests call the tool functions directly. If a future fastmcp changes this, the test
imports at the top of `tests/test_mcp_server.py` break first.

**Auth.** `TRELLO_API_KEY` + `TRELLO_TOKEN` (query params on every request, from
trello.com/power-ups/admin). No per-user scoping — single-tenant by design.

---

## Claude Code Integration

```sh
claude mcp add trello --transport http https://habittrackerdima.me/mcp/mcp
```
Start a session with `describe_board("<board>")` for a cheap overview, then `get_cards(...)` with
filters. Write plans with `create_cards([...])` in one batched call.

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Handle format / slug rules | `_slug()`, `_build_handles()` | kebab `board/list/card`, `~id4` on collision |
| Collision tiebreak length | `_build_handles()` `cid[:4]` | 4 hex chars of the Trello id |
| Fuzzy-suggestion behaviour | `difflib.get_close_matches` in the 3 resolvers | on any unresolved name/handle |
| Compact read columns | `get_cards` formatting loop | tab-delimited `handle/title/list/meta` |
| Omit-empty rules | `_clean()` | drops None/""/[]/{}, keeps 0/False |
| Checklist summary vs detail | `get_cards` uses `_checklist_counts`; `get_card` expands | `check:done/total` vs full items |
| Checklist name | `_write_checklist` → `POST /checklists name=Tasks` | currently "Tasks" |
| Batch input schemas | `NewCard` / `CardUpdate` / `CardMove` | Pydantic, sensible defaults |
| MCP server port | `mcp.run(port=...)` + `caddy/Caddyfile` | currently 8091 |
| Route prefix | `caddy/Caddyfile` `handle_path /mcp/*` + client URL | currently /mcp |
| Cron interval | `_cron_loop()` `time.sleep(3600)` | currently 1h |
| Cron board | `TRELLO_CRON_BOARD_ID` / `TRELLO_CRON_BOARD_NAME` env | |
| Cron label logic | `_cron_update_card_statuses()` | labels must exist on board |
| Trello credentials | `TRELLO_API_KEY`, `TRELLO_TOKEN` env vars | single-tenant |
