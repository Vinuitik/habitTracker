# Trello MCP Server Flow

Files: `mcp_server.py`, `tests/test_mcp_server.py`

## Overall Architecture

```
You ──► Claude / ChatGPT (with MCP connected)
           │
           ▼
  MCP endpoint (HTTPS)  habittrackerdima.me/mcp?token=…
           │
     Caddy (auth gate, no prefix strip)
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
Cloudflare → cloudflared → Caddy:80 → handle /mcp* → mongo-backup:8091
```

Caddy `handle /mcp*` does **not** strip the prefix — FastMCP's streamable-http already serves at
`/mcp`. It rejects any request whose `?token=` != `{env.MCP_TOKEN}` with 401.
To change route prefix: `caddy/Caddyfile` handle directive + `mcp.run()` path + client URL.
To change port: `mcp_server.py` `mcp.run(port=...)` + `caddy/Caddyfile` proxy target.
To change the shared secret: `MCP_TOKEN` env (docker-compose → caddy).

---

## Process Model

`start.sh` runs two processes in the `mongo-backup` container:

| Process | How | Role |
|---|---|---|
| `backup.py` | background (`&`) | MongoDB → Google Drive every 12h |
| `mcp_server.py` | foreground (`exec`) | FastMCP server + hourly cron thread |

If `mcp_server.py` exits, the container stops (PID 1 via `exec`).

---

## The planning model

The board is a planning system, not just a card store. Four rules carry it:

1. **A card is one atomic step**, finishable in a sitting — never a whole feature. Small cards
   schedule cleanly and beat procrastination.
2. **Topic lists stage features; day lists (`YYYY-MM-DD`) are what you work from.** Planning writes
   into a topic list; `apply_schedule` moves cards into dated lists. A day mixes features.
3. **Done is the `done` label.** Not "sits in a past list" — an unfinished card in a past day list
   is a *straggler* and gets pulled forward.
4. **The STATE card is what the app IS**; the graph is what's left to do. See below.

### Handles vs shortLinks

A **handle** is `board/list/card` in kebab-case — `frm/auth/google-sso`. The LLM only ever sees
handles. But a handle contains the list name, and this workflow *moves cards between lists
constantly* — so handles are unusable as stored edges. Dependency edges are therefore stored as
Trello **shortLinks** (permanent across renames *and* moves) and translated at the boundary:

```
LLM ──handle──► _handle_to_link() ──shortLink──► card desc meta block
LLM ◄─handle─── _build_handles()  ◄─shortLink─── card desc meta block
```

### Meta block

Lives in the card description, fenced, human-readable in the Trello UI:

```meta
after: 9mZt4Bc2  # Session store
feature: auth
est: 1
```

- `after` — shortLink edges, one per line (or comma-separated). Comment after `#` is cosmetic.
- `feature` — survives the move into a dated list, which destroys list-as-feature grouping.
- `est` — **load weight, not duration.** Default 1. `est>1` is a confession the card is too big;
  tools return it in `too_big` with advice to `split_card`. Nothing schedules across days.

`_parse_meta` → `(after, est, feature)`. `_set_meta` replaces the block, leaving prose intact
(idempotent). To change the format: `META_RE`, `_parse_meta`, `_render_meta`.

---

## Scheduling

`propose_schedule` (read-only) and `apply_schedule` (writes) share `_plan()` so the preview cannot
drift from the write.

```
_plan ──► _resolve_board ──► _cards ──► _schedulable ──► _schedule
                                          │                 │
                    drops done / _meta ───┘                 ├─ _build_graph  (meta → edges)
                    keeps stragglers                        ├─ _topo (Kahn)  → cycle? _find_cycle
                                                            ├─ _chain_pos    (depth / height)
                                                            └─ greedy placement
```

### `_schedule` — the algorithm

Cards are uniform-weight atomic steps, so this places **which day each card is done**, and nothing
occupies multiple days. Two modes:

| mode | window | reports |
|---|---|---|
| `deadline` given | fixed: `deadline - start + 1` | `intensity` = cards/day you signed up for |
| no `deadline` | `max(chain, ceil(total/pace))` | `end` = implied finish date |

Placement is **even bucketing of the topological order**. Walk the sorted sequence; each card's day
is set by the cumulative weight *before* it, mapped onto the window:

```
day(n) = floor( cum_before(n) / total * window )      # clamped to [0, window-1]
```

`cum` runs 0 → total, so `day` runs 0 → window-1 evenly. This is dependency-safe **for free**: `cum`
only increases and topo order places every predecessor first, so `day` is non-decreasing along real
edges → a predecessor always lands on the same day as its dependent or earlier, never later. Cards
sharing a day are still emitted in dependency order, and `apply_schedule` preserves it via
`pos="bottom"`.

This replaced an earlier greedy/chain-bounded version that **back-loaded** — it pushed leaf tasks to
7–9/day near the deadline while early days sat at 2–3. The fix (per the user: "topological sort then
divide into even buckets") is both simpler and correct: observed load for 26 cards over 20 days is
`2,1,1,1,2,1,1,1,…` — min 1, max 2, zero empty days.

To change spreading: the `cum` loop in `_schedule`. To change pace default: `DEFAULT_PACE`.

Chain longer than the window just means chained cards **share days** — allowed (you work multiple
cards/day), reported as `stacked_chain` for information only. No special-casing.

### Cycles

`_topo` returns `order=None` → `_find_cycle` (DFS colouring) → `_cycle_report` names the loop
(`A → C → B → A`) and tells the LLM to `split_card`. A cycle nearly always means a card is too
coarse — two cards each needing *part* of the other. Never silence it by deleting an edge.

### Dangling edges

An edge to a card outside the schedulable set is dropped. Usually correct (edge to a done card
constrains nothing). Genuinely unknown refs are reported in `dangling` rather than silently
ignored — a missing edge yields a *confidently wrong* schedule.

---

## Tool Surface (13 tools)

All reads omit-empty (`_clean()` drops `None`/`""`/`[]`/`{}`, keeps `0`/`False`).
The `_meta` list and the STATE card are excluded from every card read and from the scheduler.

| tool | role |
|---|---|
| `get_state(board)` | **read first.** What the app IS: built + planned. |
| `describe_graph(board)` | **read second.** Features + in-flight cards + edges. Done cards omitted. |
| `describe_board(board)` | lists + card counts. Cheap situational awareness. |
| `get_cards(...)` | compact lines; filters `list_name`/`feature`/`label`/`text`/`due_before`/`has_due`/`include_done` |
| `get_card(handle)` | full detail incl. checklist |
| `create_lists([NewList])` | batch; topic or dated |
| `create_cards(board, list_name, cards, feature?)` | batch, hoisted schema; two-pass so intra-batch edges resolve |
| `update_cards([CardUpdate])` | batch, partial; `after`/`est`/`feature` |
| `move_cards([CardMove])` | batch; the manual override |
| `split_card([CardSplit])` | break a card up, inherit edges; the cycle repair |
| `propose_schedule(...)` | read-only dated plan |
| `apply_schedule(...)` | creates day lists + bulk-moves, one call |
| `update_state(board, content)` | overwrite STATE wholesale |

### Why `describe_graph` hides done cards

A dependency on finished work constrains nothing — the scheduler computes an identical plan without
it. So done cards are never candidates, and the candidate set is bounded by **work in flight, not
history**. History grows forever; the set the LLM cross-references does not. This is what makes the
board scale without retrieval, and what the STATE card exists to backstop.

### `create_cards` — hoisted schema

`board` / `list_name` / `feature` are call-level; the batch is `[{title, after?, est?, …}]`.
`after` accepts a bare slug (same list) or a full handle (elsewhere). Two passes: create all cards,
then resolve edges — so cards in one batch may depend on each other in any order.

### `apply_schedule` — intra-day ordering for free

Rows come out of `_schedule` in topological order, and moves are applied at `pos="bottom"`. So when
several cards land on the same day, **their order within that day list IS the dependency order**.

### STATE card

`_meta/STATE`, shape of a FLOWS doc: what's built and usable, what's in progress, what's planned.
`update_state` replaces it wholesale (`get_state` → edit → `update_state`; never send a fragment).
It's what lets done cards be archived without losing the knowledge of what they built.

---

## Cron: Hourly Card Status Labels

`_cron_loop()` (daemon thread, `sleep(3600)`) → `_cron_update_card_statuses()`.
Requires `TRELLO_CRON_BOARD_ID` (or resolves `TRELLO_CRON_BOARD_NAME`); skips silently otherwise.

| Condition (cards with a due date) | Action |
|---|---|
| `due < now` | add `overdue`, remove `due-today` |
| `due.date() == today` | add `due-today`, remove `overdue` |
| no due date | skipped |

Labels `overdue` / `due-today` must exist on the board.
**Note:** nothing in the read path consumes these labels — they're purely for the visual board.
To change interval: `time.sleep(3600)`.

---

## Technology Notes

**Stateless / no slug↔id store — the core architectural decision.**
Trello stays the sole source of truth; handles are recomputed live each call. A persistent mirror
would buy rename-stable handles but introduce drift (Trello is also edited from web, mobile, and
this server's own cron). Consequences:
- **Every call hits Trello** (no cache). ~100–300ms per board fetch. Fine at ~20 cards / 1 agent;
  would need Redis + TTL, not in-process dicts, to scale horizontally.
- **Per-call caches are plain dicts**, discarded when the tool returns — correct for one process,
  structurally wrong for N instances.
- **Renaming a card changes its handle.** Edges survive (shortLink), but a handle you wrote down
  last session may not resolve.
- **Collision handles (`~id4`) are only stable while the colliding cards exist.**

**Dependencies live in card descriptions.** No graph DB. Consequences:
- Editing a description by hand in the Trello UI can corrupt the meta block. `_parse_meta` fails
  soft (returns empty) — so a mangled block **silently drops the card's edges** rather than erroring.
  That's the sharpest edge in this design.
- Rebuilding the graph is O(cards) description parses per scheduling call.

**Why no RAG / embeddings.** Considered and rejected. Dependency is a *causal* relation; embedding
similarity is a *topical* one, and they barely correlate (`db-schema` → `oauth-callback` is a real
edge with near-zero text overlap; `auth-ui` ↔ `kpi-ui` is textually near-identical with no edge).
The design that *would* work — LLM writes a query describing its need, server returns candidates,
LLM confirms — is unnecessary because `get_state` names the feature and `get_cards(feature=…)` is
then an exact **keyed lookup**, not a search. Revisit only if in-flight cards exceed ~200, at which
point the box already runs `pgvector` and `ollama`. Until then the state card + frozen-past rule
keep the candidate set small enough to just show the model outright (~1,500 tokens).

**Scheduler is greedy, not optimal.** Deterministic and explainable, which matters more here than
optimality. It will not find the perfectly balanced assignment; it finds a flat-enough one you can
reason about. No resource model beyond card count — a card is a card.

**FastMCP 3.x.** `requirements.txt` pins `fastmcp` unpinned → builds against latest 3.x. In v3
`@mcp.tool()` returns the original coroutine (registering as a side effect); there is no `.fn`
wrapper, so tests call tool functions directly. A future fastmcp changing this breaks the test
imports first.

**Auth.** `TRELLO_API_KEY` + `TRELLO_TOKEN` as query params on every request (from
trello.com/power-ups/admin). No per-user scoping — single-tenant by design. `MCP_TOKEN` gates the
endpoint at Caddy; it is a *query param*, so it appears in Caddy access logs.

---

## Claude Code Integration

```sh
claude mcp add trello --transport http "https://habittrackerdima.me/mcp?token=$MCP_TOKEN"
```

Session shape:
```
get_state → describe_graph → create_lists + create_cards → propose_schedule → apply_schedule
                                                                   │
                                         ship steps → label `done` → update_state
```

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Meta block format | `META_RE`, `_parse_meta`, `_render_meta` | ```meta fence in card desc |
| Handle format / slug rules | `_slug()`, `_build_handles()` | kebab `board/list/card`, `~id4` on collision |
| Fuzzy-suggestion behaviour | `difflib.get_close_matches` in the 3 resolvers | on any unresolved name/handle |
| Spreading algorithm | `cum` loop in `_schedule()` | even bucketing of the topo order by cumulative weight |
| Longest-chain (info) | `_longest_chain()` | reported as `chain`/`stacked_chain`, not a constraint |
| Default pace | `DEFAULT_PACE` | 2 cards/day when no deadline |
| Default estimate | `DEFAULT_EST` | 1 |
| "too big" threshold | `est > 1` in `create_cards` / `_schedule` | advice → `split_card` |
| Done marker | `DONE_LABEL` + `_is_done()` | label `done` |
| Straggler / frozen rule | `_schedulable()` | past day list + not done → pulled forward |
| STATE card location | `META_LIST`, `STATE_CARD` | `_meta/STATE` |
| Day list name format | `DATE_RE` + `apply_schedule` | `YYYY-MM-DD` |
| Intra-day ordering | `apply_schedule` `pos="bottom"` + topo row order | order in list = dep order |
| Cycle reporting | `_find_cycle()`, `_cycle_report()` | DFS colouring |
| Compact read columns | `get_cards` formatting loop | tab-delimited |
| Omit-empty rules | `_clean()` | drops None/""/[]/{}, keeps 0/False |
| Checklist name | `_write_checklist` → `POST /checklists name=Tasks` | currently "Tasks" |
| Batch input schemas | `NewCard` / `CardUpdate` / `CardMove` / `NewList` / `CardSplit` | Pydantic |
| MCP server port | `mcp.run(port=...)` + `caddy/Caddyfile` | 8091 |
| Route prefix / auth | `caddy/Caddyfile` `handle /mcp*` + `MCP_TOKEN` | no prefix strip |
| Cron interval / board / logic | `_cron_loop()`, `TRELLO_CRON_BOARD_ID`/`_NAME`, `_cron_update_card_statuses()` | 1h |
| Trello credentials | `TRELLO_API_KEY`, `TRELLO_TOKEN` env | single-tenant |
