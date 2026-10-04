# Trello MCP Server Flow

Files: `mcp_server.py`, `trello_mcp/*.py`, `tests/test_mcp_server.py`

## Module Layout

The server was split out of a single 1300-line file into the `trello_mcp/` package. Import graph
(a DAG — no cycles):

```
config.py      env, constants, _auth, SERVER_INSTRUCTIONS, the shared `mcp` (FastMCP instance), ToolError
formatting.py  pure helpers: _slug, _clean, _build_handles, _fmt_num, _short_due, _checklist_counts
meta.py        the ```meta block: _parse_meta/_render_meta/_set_meta + _is_done/_is_parked   → config, formatting
api.py         live Trello I/O: _resolve_board/_list/_handle, _cards, _label_ids, _ensure_label  → config, formatting
graph.py       pure scheduling: _build_graph, _topo, _schedule, _schedulable, _cycle_report      → config, meta, formatting
models.py      pydantic input models + LLM field docs (NewCard, CardUpdate, NewList, CardMove, CardSplit)
tools_cards.py     @mcp.tool: describe_board, get_cards, get_card, get_cards_detail,
                   create_lists/cards, update/move, complete/park/archive_cards                  → api, meta, graph, models
tools_planning.py  @mcp.tool: propose/apply_schedule, describe_graph, propose_parallel_batch,
                   get/update_state, split_card                                                  → api, graph, meta, models
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

## Internal API (port 8092, for javaapp)

Files: `internal_api.py`, `claude_cli.py`, `tests/test_internal_api.py`

```
javaapp ──(no auth)──► mongo-backup:8092 (FastAPI, thread in mcp_server.py process)
  POST   /internal/boards {name}                 → Trello POST /boards            → {boardId}
  DELETE /internal/boards/{id}                   → Trello DELETE /boards/{id}     (HARD delete)
  POST   /internal/agent/plan {boardId,description}
         → _board_name() → claude_cli.run_plan() → `claude -p` → MCP localhost:8091/mcp
           (get_state → describe_graph → create_cards → propose_schedule)         → {cards, proposal}
  POST   /internal/agent/apply {boardId,deadline?,pace?} → tools_planning.apply_schedule()
```

Not in `caddy/Caddyfile`, no published port. Boards are addressed by id at the API but the MCP tools
resolve boards by name, so `_board_name()` looks the name up first. `plan` never applies: the CLI's
allowed tools (`claude_cli.ALLOWED_TOOLS`) exclude `apply_schedule`; only `/agent/apply` moves cards.
Errors: 401 bad token, 409 plan already running, 404 board missing, 502 CLI/Trello failure, 504 CLI timeout.
The model is told to answer with one JSON object `{cards, proposal}`; `claude_cli.parse_result()` extracts it.
Delete is the one intentional hard delete (everything else archives); confirmation is the caller's job.
Empty boards are created with no default lists/labels: `Completed`/`Delayed`, `done`/`parked`, `_meta/STATE`
are created lazily by the tools.

### Technology Notes
- **Credentials mount**: `${CLAUDE_HOME:-~/.claude}` is mounted read-write at `/root/.claude` (CLI refreshes tokens).
  Anyone with container access holds the user's subscription login, and a bug in the CLI can corrupt the host dir.
  Compose may not expand `~` inside the default — set `CLAUDE_HOME` to an absolute path if the mount is empty.
  `~/.claude.json` (outside that dir) is not mounted; `-p` works without it but first-run state is not kept.
- **CLI auth expiry**: if the refresh token dies, `claude -p` exits non-zero → 502 with stderr tail. Fix: re-login on the host.
- **Memory**: Node + CLI subprocess is why mongo-backup is 768m (was 128m). Single-flight caps it at one CLI.
- **Single-flight**: `internal_api._plan_lock` is an in-process `asyncio.Lock`; concurrent plans get 409 (not queued).
  Lost on restart; fine because there is one process. `/agent/apply` is not locked.
- **Timeout/turns**: `CLAUDE_TIMEOUT_S` (300) kills the subprocess; `CLAUDE_MAX_TURNS` (25). A timed-out plan may have
  already created some cards (create_cards is not rolled back).
- **Auth**: none, by choice — network isolation only (not in Caddyfile, no published port); plain HTTP inside the docker network. Any container on that network can create/delete boards. Uvicorn runs in a daemon thread: if it dies, MCP keeps running.
- **Name resolution**: two boards with the same slugged name resolve to the first match.

---

## The planning model

The board is a planning system, not just a card store. Four rules carry it:

1. **A card is one atomic step**, finishable in a sitting — never a whole feature. Small cards
   schedule cleanly and beat procrastination.
2. **Topic lists stage features; day lists (`YYYY-MM-DD`) are what you work from.** Planning writes
   into a topic list; `apply_schedule` moves cards into dated lists. Single-developer workflow, so a
   day is feature-affine by default (see *Scheduling* → frontier priority): the scheduler finishes
   one feature's ready work before hopping to the next, and only a strictly higher-importance card
   from another feature interrupts that. A day can still mix features when that happens, or near a
   feature boundary — it is a bias, not a hard partition.
3. **Done is the `done` label, set only by `complete_cards`.** Not "sits in a past list" — an
   unfinished card in a past day list is a *straggler* and gets pulled forward. `update_cards` has
   no `labels` field; there is no other way to set it.
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

### Priority: importance, then feature affinity, ranks the frontier

`_topo` is Kahn's algorithm with the ready frontier picked each step by
`(-importance, feature_affinity, shortLink)` instead of FIFO. Precedence stays hard — only
in-degree-0 nodes are ever eligible, so a card never precedes its prerequisites regardless of
importance or feature. The frontier is a plain `set`, re-scanned with `min(key=...)` each pop rather
than a `heapq`: feature affinity depends on *which card was just picked*, a key that changes every
iteration, and a heap can't re-prioritize already-pushed entries without going stale. At board scale
(~20-30 cards) the O(n²) scan costs nothing.

- **Importance is MoSCoW 1–3** (`3` Must / `2` Should / `1` Could), stored as `importance:` in the
  meta block. Absent → `DEFAULT_IMPORTANCE` (2) at read time; never written on read (like `est`).
  Importance is the **primary** key — a Must in another feature always jumps ahead, whatever is
  currently being worked.
- **Feature affinity is the tie-break** among equal importance: `feature_affinity(n)` is `0` if
  `n`'s `feature:` matches the feature of the card just scheduled, else `1`. This is what makes a
  solo dev's schedule stay on one feature until its ready work runs dry before hopping to the next,
  addressing the original team-era design ("a day mixes features") which cost real context-switching
  once it was one person working the board. It is a soft bias, not a partition — precedence and
  importance both still win when they conflict with it.
- **No backward propagation of importance.** We deliberately do *not* compute an "effective
  importance" over descendants. Precedence already forces a blocker to run before what it blocks, so
  ranking by own importance within the frontier was judged enough — propagation was declined as
  overengineering.
- **Ties break by shortLink** for determinism (no critical-path tie-break).

The even-bucketing below then maps this order onto days, so a Must lands earlier than a Could that
was ready at the same time, and same-feature cards land on adjacent days by default. Neither **ever**
reorders across a real edge.

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

### `propose_parallel_batch` — in-degree-0 within one list

No new scheduling logic — reuses `_build_graph` exactly as `_schedule` does, but scoped to a single
list's cards instead of the whole schedulable set. Because `_build_graph` only keeps `after` edges
whose target is also in the set passed to it, scoping to one list is enough to make "ready" mean
"in-degree 0 *within this list*": an edge to a card outside the list (done, or elsewhere on the
board) is never in scope, so it can't block. Same rule `_schedulable` uses for done/parked cards.

**Only sees formal `after:` edges — not prose.** A blocker written only in a card's description
("depends on Mobile UI shell") is invisible here; the tool has no way to read intent out of free
text and isn't trying to. If a card is soft-blocked by something not expressed as an `after:` edge,
either read the description yourself before batching, or convert the blocker to a real edge with
`update_cards` so the graph — and this tool — stays authoritative.

---

## Tool Surface (18 tools)

All reads omit-empty (`_clean()` drops `None`/`""`/`[]`/`{}`, keeps `0`/`False`).
The `_meta` list and the STATE card are excluded from every card read and from the scheduler.

| tool | role |
|---|---|
| `get_state(board)` | **read first.** What the app IS: built + planned. |
| `describe_graph(board)` | **read second.** Features + in-flight cards + edges. Done cards omitted. |
| `describe_board(board)` | lists + card counts split open/done/parked. Cheap situational awareness. |
| `get_cards(...)` | compact lines; filters `list_name`/`feature`/`label`/`text`/`due_before`/`has_due`/`include_done` |
| `get_card(handle)` | full detail incl. checklist, one card |
| `get_cards_detail(board, handles)` | full detail incl. checklist, batched — one board fetch instead of N |
| `create_lists([NewList])` | batch; topic or dated |
| `create_cards(board, list_name, cards, feature?)` | batch, hoisted schema; two-pass so intra-batch edges resolve |
| `update_cards([CardUpdate])` | batch, partial; `after`/`est`/`feature`/`importance`; **no `labels` field** — use `complete_cards`/`park_cards` for done/parked |
| `move_cards([CardMove])` | batch; the manual override |
| `complete_cards(handles, done=True)` | toggle the `done` label + move into `Completed` list; the only correct way to tick |
| `park_cards(handles, parked=True)` | toggle the `parked` label + move into `Delayed` list; held out of scheduling, not done |
| `archive_cards(handles)` | close (hide) cards without deleting; for template junk |
| `split_card([CardSplit])` | break a card up, inherit edges; the cycle repair |
| `propose_schedule(...)` | read-only dated plan |
| `propose_parallel_batch(board, list_name)` | read-only; in-degree-0 cards within one list — safe to start now |
| `apply_schedule(...)` | creates day lists + bulk-moves, one call |
| `update_state(board, content)` | overwrite STATE wholesale |

### Three card states, three tools

Cards have three orthogonal "not live" states, and conflating them was the original friction:

| state | label / mechanism | scheduler | meaning |
|---|---|---|---|
| **done** | `done` label | excluded (frozen anchor) | finished |
| **parked** | `parked` label | excluded (returns on unpark) | deferred / "not now" |
| **archived** | Trello `closed=true` | gone from board | hidden junk, restorable |

`complete_cards` / `park_cards` share `_toggle_label` — both add/remove a board label, creating it on
first use so it always sticks. `archive_cards` uses `closed=true`. There is **no hard-delete** by
design — archive is reversible from the Trello UI. Parked exists so you never have to pass `lists=`
on every schedule call just to keep a list out of the plan.

**Label is truth, list is visual.** Turning a state ON also moves the card into a dedicated list —
`Completed` for done, `Delayed` for parked — auto-created on first use via `_ensure_list` (`api.py`),
same pattern as day lists in `apply_schedule`. This is **purely cosmetic**: `_schedulable` and every
read tool still key off the label alone, exactly as before. So dragging a card into `Completed` or
`Delayed` by hand in the Trello UI does **not** exclude it from scheduling — only the label does.
`update_cards` has no `labels` field at all (removed — it used to let an agent set `done`/`parked`
directly once the label already existed on the board, which set the label but skipped the list move,
producing a card that read as "done" to the scheduler but never visibly moved). `complete_cards` /
`park_cards` are now the *only* way to set or clear these labels. Turning a state OFF only removes
the label; the card is left wherever it sits, since there's no recorded origin list to return it to —
move it back with `move_cards` by hand. This was a deliberate choice over tracking origin lists: less
state, one predictable rule ("the label decides"), at the cost of a manual step when
reopening/unparking a card that's sitting in the parking list.

### Why `describe_graph` hides done cards

A dependency on finished work constrains nothing — the scheduler computes an identical plan without
it. So done cards are never candidates, and the candidate set is bounded by **work in flight, not
history**. History grows forever; the set the LLM cross-references does not. This is what makes the
board scale without retrieval, and what the STATE card exists to backstop.

### `create_cards` — hoisted schema

`board` / `list_name` / `feature` are call-level; the batch is `[{title, after?, est?, …}]`.
`after` accepts a bare slug (same list) or a full handle (elsewhere). Two passes: create all cards,
then resolve edges — so cards in one batch may depend on each other in any order.

### `apply_schedule` — intra-day ordering + day lists on the left

Rows come out of `_schedule` in topological order, and moves are applied at `pos="bottom"`. So when
several cards land on the same day, **their order within that day list IS the dependency order**.

After creating any missing day lists, apply repositions **every** day list (existing + new) to the
left of the board, chronologically. It does this by walking the dates in REVERSE order and PUTting
each to `pos="top"` — a stack: the last push (the earliest date) ends up leftmost. Feature/topic
lists keep their relative order on the right. This is pure UX: the day you work from is never a
scroll away. Costs one `PUT /lists/{id}` per day list per apply — fine at this scale.

### STATE card

`_meta/STATE`, shape of a FLOWS doc: what's built and usable, what's in progress, what's planned.
`update_state` replaces it wholesale (`get_state` → edit → `update_state`; never send a fragment).
It's what lets done cards be archived without losing the knowledge of what they built.

---

## Cron: Hourly (two sweeps)

`_cron_loop()` (daemon thread in `mcp_server.py`, `sleep(3600)`) runs two independent sweeps each
cycle. Both require `TRELLO_CRON_BOARD_ID` (or resolve `TRELLO_CRON_BOARD_NAME`); skip silently
otherwise. Each is wrapped in its own try/except so one failing doesn't stop the other.

**1. `_cron_update_card_statuses()` — overdue / due-today labels:**

| Condition (cards with a due date) | Action |
|---|---|
| `due < now` | add `overdue`, remove `due-today` |
| `due.date() == today` | add `due-today`, remove `overdue` |
| no due date | skipped |

Labels `overdue` / `due-today` must exist on the board. Nothing in the read path consumes these —
they're purely for the visual board.

**2. `_cron_archive_empty_day_lists()` — clean up spent day lists:**
Archives (Trello `closed=true`, reversible) any **open list whose name matches `YYYY-MM-DD` and
holds zero cards**. A day list is a scheduling artifact; once its cards are done or moved, it's
empty clutter. **Only DATE-named lists are touched** — feature/topic lists and `_meta` are left
alone even when empty, because an empty topic list is usually intentional (just created, about to
be filled). To broaden to all empty lists: drop the `DATE_RE.match` guard.

To change interval: `time.sleep(3600)`.

### Cleanup is both passive AND active

The cron is the *passive* sweep. The same logic also runs *actively* at the end of every write op
that shuffles cards — `apply_schedule`, `move_cards`, `archive_cards`, `split_card` — via the async
`_archive_empty_day_lists(client, board_id)` in `api.py`, so the board is tidy the instant you look
rather than up to an hour later. Those tools return `lists_cleaned` with the archived names. The
cron (`mcp_server.py`) and the async helper (`api.py`) are twins: same rule (empty `YYYY-MM-DD`
lists), different transport (sync `httpx.Client` vs async). `apply_schedule` cleans *before*
repositioning, so it never bothers to move a list it's about to archive.

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

Session shape (also sent to the client as the server's MCP `instructions`, see
`config.SERVER_INSTRUCTIONS`, so a fresh session sees this without reading FLOWS first):
```
get_state → describe_graph → create_lists + create_cards → propose_schedule → apply_schedule
                                                                   │
                                       ship steps → complete_cards → update_state
```

---

## Change Index

| What to change | Module | Where | Note |
|---|---|---|---|
| Meta block format | `meta.py` | `META_RE`, `_parse_meta`, `_render_meta` | ```meta fence in card desc |
| Handle format / slug rules | `formatting.py` | `_slug()`, `_build_handles()` | kebab `board/list/card`, `~id4` on collision |
| Fuzzy-suggestion behaviour | `api.py` | `difflib.get_close_matches` in the 3 resolvers | on any unresolved name/handle |
| Spreading algorithm | `graph.py` | `cum` loop in `_schedule()` | even bucketing of the topo order by cumulative weight |
| Frontier priority | `graph.py` | `_topo(preds, imps, feats)` `key()` | `(-importance, feature_affinity, shortLink)`; importance primary, same-feature-as-last-picked is the tie-break |
| Importance default / range | `config.py` | `DEFAULT_IMPORTANCE`, `IMPORTANCE_MIN/MAX` | 2, clamped 1–3; MoSCoW |
| Longest-chain (info) | `graph.py` | `_longest_chain()` | reported as `chain`/`stacked_chain`, not a constraint |
| Default pace | `config.py` | `DEFAULT_PACE` | 2 cards/day when no deadline |
| Default estimate | `config.py` | `DEFAULT_EST` | 1 |
| "too big" threshold | `tools_cards.py`/`graph.py` | `est > 1` in `create_cards` / `_schedule` | advice → `split_card` |
| Done marker | `config.py`/`meta.py` | `DONE_LABEL` + `_is_done()` | label `done`; set ONLY by `complete_cards` — `CardUpdate` has no `labels` field |
| Parked marker | `config.py`/`meta.py` | `PARKED_LABEL` + `_is_parked()` | label `parked`; held out of scheduling |
| Label toggle (done/parked) | `tools_cards.py` | `_toggle_label()` | shared add/remove, creates label on first use |
| Completed/Delayed parking lists | `config.py`/`api.py`/`tools_cards.py` | `COMPLETED_LIST`/`DELAYED_LIST` + `_ensure_list()` + `_toggle_label(park_list=...)` | cosmetic only — label still decides scheduling |
| Archive (hide) | `tools_cards.py` | `archive_cards()` | Trello `closed=true`; reversible, no hard-delete |
| Straggler / frozen rule | `graph.py` | `_schedulable()` | past day list + not done/parked → pulled forward |
| STATE card location | `config.py` | `META_LIST`, `STATE_CARD` | `_meta/STATE` |
| Day list name format | `config.py`/`tools_planning.py` | `DATE_RE` + `apply_schedule` | `YYYY-MM-DD` |
| Intra-day ordering | `tools_planning.py` | `apply_schedule` `pos="bottom"` + topo row order | order in list = dep order |
| Day lists to the left | `tools_planning.py` | `apply_schedule` reverse-date `PUT /lists pos=top` | earliest date leftmost |
| Empty day-list cleanup (passive) | `mcp_server.py` | `_cron_archive_empty_day_lists()` | hourly; archives empty `YYYY-MM-DD` lists only |
| Empty day-list cleanup (active) | `api.py` | `_archive_empty_day_lists()` | end of apply/move/archive/split; returns `lists_cleaned` |
| Cycle reporting | `graph.py` | `_find_cycle()`, `_cycle_report()` | DFS colouring |
| Compact read columns | `tools_cards.py` | `get_cards` formatting loop | tab-delimited |
| Batched card detail | `tools_cards.py` | `get_cards_detail()` | one board fetch for N handles; unresolved handles → `errors`, not raised |
| Parallel-batch readiness | `tools_planning.py` | `propose_parallel_batch()` | in-degree-0 within one list, via `_build_graph`; formal edges only, no prose parsing |
| Omit-empty rules | `formatting.py` | `_clean()` | drops None/""/[]/{}, keeps 0/False |
| Checklist name | `api.py` | `_write_checklist` → `POST /checklists name=Tasks` | currently "Tasks" |
| Batch input schemas | `models.py` | `NewCard` / `CardUpdate` / `CardMove` / `NewList` / `CardSplit` | Pydantic; only `NewCard` carries `labels` (creation time) |
| MCP server instructions | `config.py` | `SERVER_INSTRUCTIONS` (passed to `FastMCP(..., instructions=...)`) | sent to every client at `initialize`; steers toward `complete_cards`/`park_cards` before an agent reads FLOWS |
| MCP server port | `mcp_server.py` | `mcp.run(port=...)` + `caddy/Caddyfile` | 8091 |
| Route prefix / auth | — | `caddy/Caddyfile` `handle /mcp*` + `MCP_TOKEN` | no prefix strip |
| Cron interval / board / logic | `mcp_server.py` | `_cron_loop()`, `TRELLO_CRON_BOARD_ID`/`_NAME`, `_cron_update_card_statuses()`, `_cron_archive_empty_day_lists()` | 1h, two sweeps |
| Trello credentials | `config.py` | `TRELLO_API_KEY`, `TRELLO_TOKEN` env | single-tenant |
| Internal API routes/port | `internal_api.py` | `app`, `PORT` (8092), `start_in_thread()` | docker network only |
| Board create/hard-delete | `internal_api.py` | `create_board()`, `delete_board()` | only hard delete in the system |
| Plan single-flight | `internal_api.py` | `_plan_lock` | 409 on concurrent |
| Claude CLI invocation | `claude_cli.py` | `build_cmd()`, `ALLOWED_TOOLS`, `build_prompt()`, `parse_result()` | tools allowlist excludes apply_schedule |
| CLI limits | `claude_cli.py` | `CLAUDE_TIMEOUT_S`, `CLAUDE_MAX_TURNS`, `CLAUDE_BIN`, `CLAUDE_MCP_URL` env | 300s / 25 |
| CLI credentials mount | `docker-compose.yml` | `CLAUDE_HOME` env → `/root/.claude` | rw |
