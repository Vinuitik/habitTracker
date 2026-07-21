# Trello MCP Server Flow

Files: `mcp_server.py`, `trello_mcp/*.py`, `tests/test_mcp_server.py`

## Module Layout

The server was split out of a single 1300-line file into the `trello_mcp/` package. Import graph
(a DAG — no cycles):

```
config.py      env, constants, _auth, the shared `mcp` (FastMCP instance), ToolError
formatting.py  pure helpers: _slug, _clean, _build_handles, _fmt_num, _short_due, _checklist_counts
meta.py        the ```meta block: _parse_meta/_render_meta/_set_meta + _is_done/_is_parked   → config, formatting
api.py         live Trello I/O: _resolve_board/_list/_handle, _cards, _label_ids, _ensure_label  → config, formatting
graph.py       pure scheduling: _build_graph, _topo, _schedule, _schedulable, _cycle_report      → config, meta, formatting
models.py      pydantic input models + LLM field docs (NewCard, CardUpdate, NewList, CardMove, CardSplit)
tools_cards.py     @mcp.tool: describe_board, get_cards, get_card, create_lists/cards, update/move,
                   complete/park/archive_cards                                                   → api, meta, graph, models
tools_planning.py  @mcp.tool: propose/apply_schedule, describe_graph, get/update_state, split_card → api, graph, meta, models
mcp_server.py  entrypoint: re-exports everything, keeps the cron, runs mcp.run()
```

**Why the cron stays in `mcp_server.py`** and everything else moved: the tests set
`mcp_server.TRELLO_CRON_BOARD_ID` / `_NAME` directly, so the cron functions must read those from
this module's namespace. **Why `mcp_server` still `import httpx`**: the tests patch
`mcp_server.httpx.AsyncClient` / `.Client`; `import httpx` is one shared module object, so patching
it here also patches the httpx used inside every submodule. Adding a tool = add it to the relevant
`tools_*.py` (it registers via `@mcp.tool()` on import) and re-export it from `mcp_server.py`.

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
importance: 3
est: 1
```

- `after` — shortLink edges, one per line (or comma-separated). Comment after `#` is cosmetic.
- `feature` — survives the move into a dated list, which destroys list-as-feature grouping.
- `importance` — MoSCoW 1–3 (3 Must / 2 Should / 1 Could). Default 2. Ranks the scheduling frontier;
  never overrides dependencies. See *Priority* below.
- `est` — **load weight, not duration.** Default 1. `est>1` is a confession the card is too big;
  tools return it in `too_big` with advice to `split_card`. Nothing schedules across days.

`_parse_meta` → dict `{after, est, feature, importance}` (absent values `None`; defaults applied by
callers, so a card without a line stays clean on disk). `_set_meta` replaces the block, leaving prose
intact
(idempotent). To change the format: `META_RE`, `_parse_meta`, `_render_meta`.

---

## Scheduling

`propose_schedule` (read-only) and `apply_schedule` (writes) share `_plan()` so the preview cannot
drift from the write.

```
_plan ──► _resolve_board ──► _cards ──► _schedulable ──► _schedule
                                          │                 │
                    drops done / _meta ───┘                 ├─ _build_graph  (meta → edges, ests, imps)
                    keeps stragglers                        ├─ _topo(preds, imps) → cycle? _find_cycle
                                                            └─ even bucketing → days
```

### Priority: importance ranks the frontier

`_topo` is Kahn's algorithm with the ready frontier as a **max-priority queue on importance** (a
`heapq`, key `(-importance, shortLink)`) instead of FIFO. Precedence stays hard — only in-degree-0
nodes are ever popped, so a card never precedes its prerequisites regardless of importance. Importance
only decides which of the *currently-unblocked* cards comes next; as each is scheduled it unlocks its
successors, which then compete in the moving frontier.

- **Importance is MoSCoW 1–3** (`3` Must / `2` Should / `1` Could), stored as `importance:` in the
  meta block. Absent → `DEFAULT_IMPORTANCE` (2) at read time; never written on read (like `est`).
- **No backward propagation.** We deliberately do *not* compute an "effective importance" over
  descendants. Precedence already forces a blocker to run before what it blocks, so ranking by own
  importance within the frontier was judged enough — propagation was declined as overengineering.
- **Ties break by shortLink** for determinism (no critical-path tie-break).

The even-bucketing below then maps this importance-ranked order onto days, so a Must lands earlier
than a Could that was ready at the same time. It **never** reorders across a real edge.

### `_schedule` — the placement

Cards are uniform-weight atomic steps, so this places **which day each card is done**, and nothing
occupies multiple days. Two modes:

| mode | window | reports |
|---|---|---|
| `deadline` given | fixed: `deadline - start + 1` | `intensity` = cards/day you signed up for |
| no `deadline` | `max(chain, ceil(total/pace))` | `end` = implied finish date |

Placement is **even bucketing of the (importance-ranked) topological order**. Walk the sorted
sequence; each card's day is set by the cumulative weight *before* it, mapped onto the window:

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

## Tool Surface (16 tools)

All reads omit-empty (`_clean()` drops `None`/`""`/`[]`/`{}`, keeps `0`/`False`).
The `_meta` list and the STATE card are excluded from every card read and from the scheduler.

| tool | role |
|---|---|
| `get_state(board)` | **read first.** What the app IS: built + planned. |
| `describe_graph(board)` | **read second.** Features + in-flight cards + edges. Done cards omitted. |
| `describe_board(board)` | lists + card counts split open/done/parked. Cheap situational awareness. |
| `get_cards(...)` | compact lines; filters `list_name`/`feature`/`label`/`text`/`due_before`/`has_due`/`include_done` |
| `get_card(handle)` | full detail incl. checklist |
| `create_lists([NewList])` | batch; topic or dated |
| `create_cards(board, list_name, cards, feature?)` | batch, hoisted schema; two-pass so intra-batch edges resolve |
| `update_cards([CardUpdate])` | batch, partial; `after`/`est`/`feature`/`importance` |
| `move_cards([CardMove])` | batch; the manual override |
| `complete_cards(handles, done=True)` | toggle the `done` label; the only correct way to tick |
| `park_cards(handles, parked=True)` | toggle the `parked` label; held out of scheduling, not done |
| `archive_cards(handles)` | close (hide) cards without deleting; for template junk |
| `split_card([CardSplit])` | break a card up, inherit edges; the cycle repair |
| `propose_schedule(...)` | read-only dated plan |
| `apply_schedule(...)` | creates day lists + bulk-moves, one call |
| `update_state(board, content)` | overwrite STATE wholesale |

### Three card states, three tools

Cards have three orthogonal "not live" states, and conflating them was the original friction:

| state | label / mechanism | scheduler | meaning |
|---|---|---|---|
| **done** | `done` label | excluded (frozen anchor) | finished |
| **parked** | `parked` label | excluded (returns on unpark) | deferred / "not now" |
| **archived** | Trello `closed=true` | gone from board | hidden junk, restorable |

`complete_cards` / `park_cards` share `_toggle_label` — both just add/remove a board label, creating
it on first use (so it always sticks; a hand-set label via `update_cards` silently fails if the label
doesn't exist yet). `archive_cards` uses `closed=true`. There is **no hard-delete** by design —
archive is reversible from the Trello UI. Parked exists so you never have to pass `lists=` on every
schedule call just to keep a list out of the plan.

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
`@mcp.tool()` returns the original coroutine (registering it on the shared `mcp` as a side effect);
there is no `.fn` wrapper, so tests import the tool functions (re-exported from `mcp_server`) and
call them directly. A future fastmcp changing this breaks the test imports first. All tool modules
register on the single `mcp` created in `config.py`; `mcp_server.py` importing them is what triggers
registration before `mcp.run()`.

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

| What to change | Module | Where | Note |
|---|---|---|---|
| Meta block format | `meta.py` | `META_RE`, `_parse_meta`, `_render_meta` | ```meta fence in card desc |
| Handle format / slug rules | `formatting.py` | `_slug()`, `_build_handles()` | kebab `board/list/card`, `~id4` on collision |
| Fuzzy-suggestion behaviour | `api.py` | `difflib.get_close_matches` in the 3 resolvers | on any unresolved name/handle |
| Spreading algorithm | `graph.py` | `cum` loop in `_schedule()` | even bucketing of the topo order by cumulative weight |
| Frontier priority | `graph.py` | `_topo(preds, imps)` heap key | `(-importance, shortLink)`; importance ranks ready set |
| Importance default / range | `config.py` | `DEFAULT_IMPORTANCE`, `IMPORTANCE_MIN/MAX` | 2, clamped 1–3; MoSCoW |
| Longest-chain (info) | `graph.py` | `_longest_chain()` | reported as `chain`/`stacked_chain`, not a constraint |
| Default pace | `config.py` | `DEFAULT_PACE` | 2 cards/day when no deadline |
| Default estimate | `config.py` | `DEFAULT_EST` | 1 |
| "too big" threshold | `tools_cards.py`/`graph.py` | `est > 1` in `create_cards` / `_schedule` | advice → `split_card` |
| Done marker | `config.py`/`meta.py` | `DONE_LABEL` + `_is_done()` | label `done` |
| Parked marker | `config.py`/`meta.py` | `PARKED_LABEL` + `_is_parked()` | label `parked`; held out of scheduling |
| Label toggle (done/parked) | `tools_cards.py` | `_toggle_label()` | shared add/remove, creates label on first use |
| Archive (hide) | `tools_cards.py` | `archive_cards()` | Trello `closed=true`; reversible, no hard-delete |
| Straggler / frozen rule | `graph.py` | `_schedulable()` | past day list + not done/parked → pulled forward |
| STATE card location | `config.py` | `META_LIST`, `STATE_CARD` | `_meta/STATE` |
| Day list name format | `config.py`/`tools_planning.py` | `DATE_RE` + `apply_schedule` | `YYYY-MM-DD` |
| Intra-day ordering | `tools_planning.py` | `apply_schedule` `pos="bottom"` + topo row order | order in list = dep order |
| Cycle reporting | `graph.py` | `_find_cycle()`, `_cycle_report()` | DFS colouring |
| Compact read columns | `tools_cards.py` | `get_cards` formatting loop | tab-delimited |
| Omit-empty rules | `formatting.py` | `_clean()` | drops None/""/[]/{}, keeps 0/False |
| Checklist name | `api.py` | `_write_checklist` → `POST /checklists name=Tasks` | currently "Tasks" |
| Batch input schemas | `models.py` | `NewCard` / `CardUpdate` / `CardMove` / `NewList` / `CardSplit` | Pydantic |
| MCP server port | `mcp_server.py` | `mcp.run(port=...)` + `caddy/Caddyfile` | 8091 |
| Route prefix / auth | — | `caddy/Caddyfile` `handle /mcp*` + `MCP_TOKEN` | no prefix strip |
| Cron interval / board / logic | `mcp_server.py` | `_cron_loop()`, `TRELLO_CRON_BOARD_ID`/`_NAME`, `_cron_update_card_statuses()` | 1h |
| Trello credentials | `config.py` | `TRELLO_API_KEY`, `TRELLO_TOKEN` env | single-tenant |
