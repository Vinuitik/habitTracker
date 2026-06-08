# Trello MCP Server Flow

Files: `mcp_server.py`

## Overall Architecture

```
You ──► Claude / ChatGPT (with MCP connected)
           │
           ▼
  MCP SSE endpoint (HTTPS)
  habittrackerdima.me/mcp/sse
           │
     Caddy strips /mcp
           │
           ▼
  mongo-backup:8091 (FastMCP SSE)
           │
           ▼
     Trello REST API

Claude Code ──► same MCP endpoint ──► reads cards ──► executes tasks

Background thread (every 1h) ──► labels overdue / due-today cards
```

## Ingress Chain

```
Cloudflare → cloudflared → Caddy:80 → handle_path /mcp/* → mongo-backup:8091
```

Caddy `handle_path /mcp/*` strips the `/mcp` prefix before proxying:
- Client connects to `https://habittrackerdima.me/mcp/sse` → FastMCP sees `GET /sse`
- Client POSTs to `https://habittrackerdima.me/mcp/messages` → FastMCP sees `POST /messages`

To change route prefix: `caddy/Caddyfile` handle_path directive + MCP client config URL.
To change port: `mcp_server.py` `mcp.run(port=...)` + `caddy/Caddyfile` proxy target.

---

## Process Model

`start.sh` runs two processes in the `mongo-backup` container:

| Process | How | Role |
|---|---|---|
| `backup.py` | background (`&`) | MongoDB → Google Drive every 12h |
| `mcp_server.py` | foreground (`exec`) | FastMCP SSE server + hourly cron thread |

If `mcp_server.py` exits, the container stops (it is PID 1 via `exec`).
If `backup.py` exits, the container keeps running; backup failures are silent (caught by `try/except`).

---

## Tool Flows

### list_boards_and_lists
No input.
`GET /members/me/boards?fields=name,id&lists=open` → returns all boards with nested lists (id + name).
Call this first in any session to resolve human-readable names to IDs before creating/querying cards.

### create_card
1. `GET /lists/{list_id}?fields=idBoard` → resolve `board_id` from the target list
2. `GET /boards/{board_id}/labels` → map label names → label IDs (unknown names silently skipped)
3. `POST /cards` with `idList`, `name`, `desc`, optional `due`, optional `idLabels`
4. If `checklist_items` provided:
   - `POST /checklists?idCard={card_id}&name=Tasks` → create checklist
   - `POST /checklists/{cl_id}/checkItems?name={item}` × N → add each item
Returns `{card_id, card_url, title}`.

### update_card
Explicit named parameters — only provided (non-None) params are sent to Trello.
**Bug history:** the previous `fields: dict` signature caused silent no-ops when LLMs passed
Trello field names (`idList`, `due`) instead of MCP names (`list_id`, `due_date`), and `updated_fields`
always echoed back what the caller passed regardless of what was actually sent. Both fixed 2026-06-08.

- `title` / `description` / `due_date` / `list_id`: batched into one `PUT /cards/{id}`
- `labels`: fetch board labels → remap names → `PUT /cards/{id}` with new `idLabels` (replaces all)
- `checklist_items`: delete all existing checklists → create fresh "Tasks" checklist → add items

`due_date="null"` (the string) clears the due date. `due_date=None` (Python default) means "don't touch".
`updated_fields` in the response reflects only what was actually applied, not what was passed in.

### get_urgent_cards
1. `GET /boards/{board_id}/lists` → build target list ID set
   - If `list_names` provided: include only those lists
   - Otherwise: all lists except `{"Done", "Archive"}`
2. `GET /boards/{board_id}/cards?checklists=all` → filter to target lists
3. Sort key: `(0, due_date, not_urgent)` → `(1, _, _)` urgent/no-due → `(2, _, _)` rest
4. Return top `limit` cards with full checklist item state

To change excluded lists: `get_urgent_cards` `exclude` set in `mcp_server.py`.

### get_all_cards
1. If `exclude_lists` provided: `GET /boards/{board_id}/lists` → collect IDs to skip
2. `GET /boards/{board_id}/cards?checklists=all` → filter out skipped list IDs
3. Returns every remaining card with full fields (title, desc, due, labels, checklist items)

Use when you need a full inventory of a board rather than a priority-sorted slice.

### create_list
1. `POST /lists` with `idBoard`, `name`, optional `pos` (default `"bottom"`)
2. Returns `{list_id, list_name, board_id}`

Use to create new lists on a board before populating them with cards.

---

## Cron: Hourly Card Status Labels

`_cron_loop()` runs in a `daemon=True` background thread, sleeping 1h between runs.
`_cron_update_card_statuses()` does the actual work.

Requires `TRELLO_CRON_BOARD_ID` to be set; skips silently otherwise.

**Logic per card (cards with a due date only):**

| Condition | Action |
|---|---|
| `due_dt < now` | add `overdue` label, remove `due-today` label |
| `due_dt.date() == today` | add `due-today` label, remove `overdue` label |
| No due date | skipped |

Labels `overdue` and `due-today` must be created manually on the board.
Cards where the computed label set is unchanged are not touched (no unnecessary API calls).

To change interval: `time.sleep(3600)` in `_cron_loop()`.
To extend cron logic (e.g. move cards between lists): `_cron_update_card_statuses()`.

---

## Claude Code Integration

Add to Claude Code MCP config (run once in the project):
```sh
claude mcp add trello --transport sse https://habittrackerdima.me/mcp/sse
```

Or add to `.claude/mcp.json`:
```json
{
  "mcpServers": {
    "trello": {
      "type": "sse",
      "url": "https://habittrackerdima.me/mcp/sse"
    }
  }
}
```

At the start of a coding session, call `get_urgent_cards` to know what to work on next.

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| MCP server port | `mcp_server.py` `mcp.run(port=...)` + `caddy/Caddyfile` proxy target | currently 8091 |
| Route prefix | `caddy/Caddyfile` `handle_path /mcp/*` + MCP client URL | currently /mcp |
| Cron interval | `mcp_server.py` `_cron_loop()` `time.sleep(3600)` | currently 1h |
| Cron board | `TRELLO_CRON_BOARD_ID` env var in `.env` / docker-compose | |
| Cron label logic | `mcp_server.py` `_cron_update_card_statuses()` | labels must exist on board |
| Checklist name | `create_card` / `update_card` → `POST /checklists name=Tasks` | currently "Tasks" |
| Excluded lists (urgent query) | `get_urgent_cards` `exclude` set | currently `{"Done", "Archive"}` |
| New list position | `create_list` `pos` param | `"top"`, `"bottom"`, or integer |
| Trello credentials | `TRELLO_API_KEY`, `TRELLO_TOKEN` env vars | from trello.com/power-ups/admin |
