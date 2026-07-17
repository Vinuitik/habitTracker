import difflib
import os
import re
import threading
import time
from collections import Counter, defaultdict, deque
from datetime import date, datetime, timedelta, timezone

import httpx
from fastmcp import FastMCP
from pydantic import BaseModel, Field

try:
    from fastmcp.exceptions import ToolError
except Exception:  # pragma: no cover - older/newer fastmcp
    class ToolError(Exception):
        pass

TRELLO_API_KEY = os.getenv("TRELLO_API_KEY", "")
TRELLO_TOKEN = os.getenv("TRELLO_TOKEN", "")
TRELLO_CRON_BOARD_ID = os.getenv("TRELLO_CRON_BOARD_ID", "")
TRELLO_CRON_BOARD_NAME = os.getenv("TRELLO_CRON_BOARD_NAME", "")
TRELLO_BASE = "https://api.trello.com/1"

mcp = FastMCP("HabitTracker Trello")


def _auth() -> dict:
    return {"key": TRELLO_API_KEY, "token": TRELLO_TOKEN}


# ── Handles & formatting ────────────────────────────────────────────────────────
#
# A handle is `board/list/card` in kebab-case, e.g. "frm/auth/google-sso".
# Handles are NOT stored — they are derived from live Trello names on every call and
# resolved back to Trello IDs server-side. Trello is the single source of truth; there
# is no mirror to drift. On a name collision within one list we append a deterministic
# tiebreak from the real card id (`~a1b2`), so a handle always points at one card.

def _slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", (s or "").lower()).strip("-") or "untitled"


def _short_due(due: str | None) -> str | None:
    return due[:10] if due else None


def _clean(d: dict) -> dict:
    """Omit-empty: drop keys whose value is None / "" / [] / {}. Keeps 0 and False."""
    return {k: v for k, v in d.items() if v not in (None, "", [], {})}


def _checklist_counts(card: dict) -> tuple[int, int]:
    items = [it for cl in card.get("checklists", []) for it in cl.get("checkItems", [])]
    return sum(1 for it in items if it["state"] == "complete"), len(items)


def _build_handles(cards: list[dict], board_slug: str, list_name_by_id: dict) -> dict:
    """Map card id → handle. Appends `~<id4>` only where two cards in a list collide."""
    base = {c["id"]: (_slug(list_name_by_id.get(c["idList"], "list")), _slug(c["name"])) for c in cards}
    counts = Counter(base.values())
    handles = {}
    for cid, (ls, cs) in base.items():
        h = f"{board_slug}/{ls}/{cs}"
        if counts[(ls, cs)] > 1:
            h += f"~{cid[:4]}"
        handles[cid] = h
    return handles


# ── Live resolution (per-call caches, no persistent state) ───────────────────────

async def _get(client: httpx.AsyncClient, path: str, **params) -> object:
    r = await client.get(f"{TRELLO_BASE}{path}", params={**_auth(), **params})
    r.raise_for_status()
    return r.json()


async def _boards(client, cache: dict) -> list[dict]:
    if "boards" not in cache:
        cache["boards"] = await _get(client, "/members/me/boards", fields="name,id", lists="open")
    return cache["boards"]


async def _resolve_board(client, name: str, cache: dict) -> dict:
    boards = await _boards(client, cache)
    target = _slug(name)
    for b in boards:
        if _slug(b["name"]) == target:
            return b
    names = [b["name"] for b in boards]
    sug = difflib.get_close_matches(name, names, n=3)
    hint = f" Did you mean: {', '.join(sug)}?" if sug else (f" Known boards: {', '.join(names)}." if names else "")
    raise ToolError(f"No board matching '{name}'.{hint}")


def _resolve_list(board: dict, name: str) -> dict:
    target = _slug(name)
    lists = board.get("lists", [])
    for l in lists:
        if _slug(l["name"]) == target:
            return l
    names = [l["name"] for l in lists]
    sug = difflib.get_close_matches(name, names, n=3)
    hint = f" Did you mean: {', '.join(sug)}?" if sug else (f" Lists on '{board['name']}': {', '.join(names)}." if names else "")
    raise ToolError(f"No list matching '{name}' on board '{board['name']}'.{hint}")


async def _cards(client, board_id: str, cache: dict, checklists: bool = False) -> list[dict]:
    key = f"cards:{board_id}:{checklists}"
    if key not in cache:
        params = {"fields": "name,desc,due,idList,labels,shortUrl,shortLink"}
        if checklists:
            params["checklists"] = "all"
        cache[key] = await _get(client, f"/boards/{board_id}/cards", **params)
    return cache[key]


async def _resolve_handle(client, handle: str, cache: dict, checklists: bool = False) -> tuple[dict, dict, dict]:
    """handle 'board/list/card[~tie]' → (board, list, card). Teaches on miss."""
    parts = handle.split("/")
    if len(parts) < 3:
        raise ToolError(f"Handle '{handle}' must look like board/list/card (e.g. frm/auth/google-sso).")
    board_slug, list_slug, card_part = parts[0], parts[1], "/".join(parts[2:])
    card_slug, tie = (card_part.split("~", 1) + [None])[:2] if "~" in card_part else (card_part, None)

    board = await _resolve_board(client, board_slug, cache)
    lst = _resolve_list(board, list_slug)
    cards = await _cards(client, board["id"], cache, checklists)
    in_list = [c for c in cards if c["idList"] == lst["id"]]
    matches = [c for c in in_list if _slug(c["name"]) == _slug(card_slug)]
    if tie:
        matches = [c for c in matches if c["id"].startswith(tie)]

    if not matches:
        sug = difflib.get_close_matches(_slug(card_slug), [_slug(c["name"]) for c in in_list], n=3)
        hint = f" Did you mean: {', '.join(f'{board_slug}/{list_slug}/{s}' for s in sug)}?" if sug else ""
        raise ToolError(f"No card '{card_slug}' in {board_slug}/{list_slug}.{hint}")
    if len(matches) > 1:
        opts = ", ".join(f"{board_slug}/{list_slug}/{_slug(c['name'])}~{c['id'][:4]}" for c in matches)
        raise ToolError(f"Ambiguous handle '{handle}' matches {len(matches)} cards. Disambiguate: {opts}")
    return board, lst, matches[0]


async def _label_ids(client, board_id: str, names: list[str], cache: dict) -> list[str]:
    if not names:
        return []
    if board_id not in cache:
        r = await client.get(f"{TRELLO_BASE}/boards/{board_id}/labels", params=_auth())
        r.raise_for_status()
        cache[board_id] = {_slug(l["name"]): l["id"] for l in r.json() if l["name"]}
    m = cache[board_id]
    return [m[_slug(n)] for n in names if _slug(n) in m]


# ── Dependency meta block ───────────────────────────────────────────────────────
#
# Dependencies and estimates live in a fenced ```meta block in the card description.
# Edges reference Trello's shortLink — permanent, and crucially stable across BOTH
# renames and list moves. Handles are `board/list/card`, so a card moving from a topic
# list into a dated list changes its handle; an edge stored as a handle would dangle on
# the single most common action in this workflow. The `# comment` after each link keeps
# the block readable in the Trello UI. The LLM only ever sees handles — we translate.

META_RE = re.compile(r"```meta\s*\n(.*?)\n?```", re.S)
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
DEFAULT_EST = 1.0
META_LIST = "_meta"       # holds the STATE card; excluded from every read and the scheduler
STATE_CARD = "STATE"
DONE_LABEL = "done"
DEFAULT_PACE = 2.0        # cards/day when no deadline is given


def _fmt_num(x: float) -> str:
    return str(int(x)) if float(x).is_integer() else f"{x:g}"


def _parse_meta(desc: str) -> tuple[list[str], float | None, str | None]:
    """desc → (after:[shortLink], est, feature). Missing/malformed block → ([], None, None)."""
    m = META_RE.search(desc or "")
    if not m:
        return [], None, None
    after: list[str] = []
    est: float | None = None
    feature: str | None = None
    for raw in m.group(1).splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        k, _, v = line.partition(":")
        k, v = k.strip().lower(), v.strip()
        if k == "after" and v:
            after.extend(p.strip() for p in v.split(",") if p.strip())
        elif k == "est" and v:
            try:
                est = float(v)
            except ValueError:
                pass
        elif k == "feature" and v:
            feature = v
    return after, est, feature


def _render_meta(after: list[tuple[str, str]], est: float | None, feature: str | None) -> str:
    lines = [f"after: {link}  # {title}" for link, title in after]
    if feature:
        lines.append(f"feature: {feature}")
    if est is not None:
        lines.append(f"est: {_fmt_num(est)}")
    return "```meta\n" + "\n".join(lines) + "\n```" if lines else ""


def _set_meta(desc: str, after: list[tuple[str, str]], est: float | None, feature: str | None = None) -> str:
    """Replace the meta block in desc, leaving the human prose untouched."""
    body = META_RE.sub("", desc or "").strip()
    block = _render_meta(after, est, feature)
    return f"{body}\n\n{block}".strip() if block else body


def _is_done(card: dict) -> bool:
    return any(_slug(l.get("name", "")) == DONE_LABEL for l in card.get("labels", []))


# ── Graph ───────────────────────────────────────────────────────────────────────

def _build_graph(cards: list[dict]) -> tuple[dict, dict, dict, list[dict]]:
    """cards → (by_link, preds, ests, dangling). Edges pointing outside the set (typically to
    an already-done card) are dropped: a dependency on finished work constrains nothing. Edges
    to genuinely unknown refs are reported rather than silently ignored — a missing edge yields
    a confidently wrong schedule, which is worse than no schedule."""
    by_link = {c["shortLink"]: c for c in cards}
    preds: dict[str, list[str]] = {}
    ests: dict[str, float] = {}
    dangling: list[dict] = []
    for c in cards:
        link = c["shortLink"]
        after, est, _ = _parse_meta(c.get("desc"))
        preds[link] = [a for a in after if a in by_link]
        ests[link] = est if est is not None else DEFAULT_EST
        for a in after:
            if a not in by_link:
                dangling.append({"card": c["name"], "unknown_ref": a})
    return by_link, preds, ests, dangling


def _find_cycle(preds: dict[str, list[str]]) -> list[str] | None:
    """DFS with colouring. Returns one cycle as a list of links, or None."""
    WHITE, GREY, BLACK = 0, 1, 2
    color = dict.fromkeys(preds, WHITE)
    stack: list[str] = []

    def dfs(n: str) -> list[str] | None:
        color[n] = GREY
        stack.append(n)
        for p in preds[n]:
            if color[p] == GREY:
                return stack[stack.index(p):] + [p]
            if color[p] == WHITE:
                found = dfs(p)
                if found:
                    return found
        stack.pop()
        color[n] = BLACK
        return None

    for n in preds:
        if color[n] == WHITE:
            found = dfs(n)
            if found:
                return found
    return None


def _topo(preds: dict[str, list[str]]) -> tuple[list[str] | None, dict[str, list[str]]]:
    """Kahn. Returns (order, succs); order is None iff a cycle exists."""
    succs: dict[str, list[str]] = defaultdict(list)
    indeg = {n: 0 for n in preds}
    for n, ps in preds.items():
        for p in ps:
            succs[p].append(n)
            indeg[n] += 1
    q = deque(sorted(n for n, d in indeg.items() if d == 0))
    order: list[str] = []
    while q:
        n = q.popleft()
        order.append(n)
        for s in sorted(succs[n]):
            indeg[s] -= 1
            if indeg[s] == 0:
                q.append(s)
    return (order if len(order) == len(preds) else None), succs


def _chain_pos(order: list[str], preds: dict[str, list[str]], succs: dict[str, list[str]]):
    """depth = cards in the longest chain ENDING at n (how early n can possibly be).
    height = cards in the longest chain STARTING at n (how late n can possibly be, or it drags
    its dependents past the deadline). Counted in cards, not time — a card is one day at most."""
    depth: dict[str, int] = {}
    for n in order:
        depth[n] = 1 + max((depth[p] for p in preds[n]), default=0)
    height: dict[str, int] = {}
    for n in reversed(order):
        height[n] = 1 + max((height[s] for s in succs[n]), default=0)
    return depth, height


def _schedule(cards: list[dict], deadline: str | None = None, start: str | None = None,
              pace: float = DEFAULT_PACE) -> dict:
    """Topological order + even spreading of card COUNT across the window.

    Cards are atomic steps of uniform weight, so `est` is a load figure, not a duration — nothing
    here models a task occupying multiple days. Two modes:
      deadline given → window is fixed; target load = total/window. The plan always fits; how hard
                       you are pushing comes back as `intensity` (cards/day).
      no deadline    → schedule at `pace` cards/day and report the implied end date.
    A chain longer than the window forces stacking, which is legal (multiple cards/day) but reported.
    """
    by_link, preds, ests, dangling = _build_graph(cards)
    if not by_link:
        return {"error": "No cards to schedule."}

    order, succs = _topo(preds)
    if order is None:
        return {"cycle": _find_cycle(preds) or [], "by_link": by_link}

    start_d = date.fromisoformat(start) if start else date.today()
    total = sum(ests.values())
    depth, height = _chain_pos(order, preds, succs)
    chain = max(depth.values(), default=0)

    if deadline:
        end_d = date.fromisoformat(deadline)
        window = (end_d - start_d).days + 1
        if window < 1:
            return {"error": f"Deadline {deadline} is not after start {start_d.isoformat()}."}
    else:  # natural pace — the schedule defines its own end
        window = max(chain, int(-(-total // pace)))

    # Greedy in topological order, placing each card on the least-loaded day still open to it
    # (ties → earliest). Two bounds keep it honest:
    #   lo — its chain depth, and strictly after every predecessor already placed
    #   hi — window minus its chain height, so parking it late cannot push its dependents past
    #        the deadline. Without hi, least-loaded is myopic: it drops an independent card on a
    #        far empty day and strands the chain behind it (and first-fit instead just fills the
    #        front and dumps the remainder on the last day).
    # Chain longer than the window → hi < lo, we clamp, and chained cards stack. Legal here, and
    # reported as `stacked_chain`.
    load: dict[int, float] = defaultdict(float)
    day_of: dict[str, int] = {}
    for n in order:
        lo = max([depth[n] - 1] + [day_of[p] + 1 for p in preds[n]])
        lo = min(lo, window - 1)
        hi = max(lo, min(window - height[n], window - 1))
        best = min(range(lo, hi + 1), key=lambda d: (load[d], d))
        day_of[n] = best
        load[best] += ests[n]

    rows = [{
        "link": n,
        "card": by_link[n]["name"],
        "feature": _parse_meta(by_link[n].get("desc"))[2] or "",
        "date": (start_d + timedelta(days=day_of[n])).isoformat(),
        "est": ests[n],
    } for n in order]  # topological order preserved — apply_schedule relies on it

    return {
        "rows": rows,
        "total": total,
        "window_days": window,
        "intensity": round(total / window, 2) if window else 0,
        "chain": chain,
        "stacked_chain": chain > window,
        "end": (start_d + timedelta(days=window - 1)).isoformat(),
        "dangling": dangling,
        "too_big": [{"card": by_link[n]["name"], "est": ests[n]} for n in order if ests[n] > 1],
        "load": {(start_d + timedelta(days=d)).isoformat(): round(load[d], 2) for d in range(window)},
        "by_link": by_link,
    }


async def _write_checklist(client, card_id: str, items: list[str]) -> None:
    r = await client.post(f"{TRELLO_BASE}/checklists", params={**_auth(), "idCard": card_id, "name": "Tasks"})
    r.raise_for_status()
    cl_id = r.json()["id"]
    for item in items:
        await client.post(f"{TRELLO_BASE}/checklists/{cl_id}/checkItems", params={**_auth(), "name": item})


# ── Input models (typed so the LLM sees a clear schema with sensible defaults) ────

_AFTER_DOC = (
    "Cards that must be DONE before this one starts — the dependency graph. Declare it and the "
    "server does the topological sort and scheduling; never try to order tasks yourself, and never "
    "encode order by listing cards in sequence. Two forms: a bare slug ('user-model') refers to a "
    "card in the same list, a full handle ('frm/kpi/kpi-model') to one elsewhere. Do NOT add edges "
    "to cards that are already done — a finished dependency constrains nothing and is dropped."
)
_EST_DOC = (
    "Load weight, NOT duration. A card is one atomic step you can finish in a single sitting, so "
    "this should be 1 (the default — just omit it). Setting est>1 is a confession that the card is "
    "really several cards: expect the tool to tell you to split it. Nothing here schedules a card "
    "across multiple days."
)
_FEATURE_DOC = (
    "Feature slug this card belongs to, e.g. 'auth'. Defaults to the list name. Stored on the card "
    "itself because cards get moved into dated lists, which destroys the list-as-feature grouping."
)


class NewCard(BaseModel):
    title: str
    description: str = ""
    labels: list[str] = Field(default_factory=list, description="Existing label names on the board; unknown names are skipped")
    due: str | None = Field(default=None, description="ISO 8601 due date, or omit for none")
    checklist: list[str] = Field(default_factory=list)
    after: list[str] = Field(default_factory=list, description=_AFTER_DOC + " May reference cards created in this same batch.")
    est: float | None = Field(default=None, description=_EST_DOC)


class CardUpdate(BaseModel):
    handle: str
    title: str | None = None
    description: str | None = None
    labels: list[str] | None = Field(default=None, description="Replaces all labels when given")
    due: str | None = Field(default=None, description="ISO 8601 to set, the string 'null' to clear, omit to leave unchanged")
    checklist: list[str] | None = Field(default=None, description="Replaces the checklist when given")
    after: list[str] | None = Field(default=None, description=_AFTER_DOC + " Replaces all edges when given; pass [] to clear.")
    est: float | None = Field(default=None, description=_EST_DOC)
    feature: str | None = Field(default=None, description=_FEATURE_DOC)


class NewList(BaseModel):
    board: str = Field(description="Board name or slug")
    name: str = Field(description="List name. Use a topic name, or an ISO date (YYYY-MM-DD) for a day list.")
    pos: str = "bottom"


class CardMove(BaseModel):
    handle: str
    to_list: str = Field(description="Destination list name or slug")
    pos: str = "bottom"


# ── Tools ────────────────────────────────────────────────────────────────────────

@mcp.tool()
async def describe_board(board: str) -> dict:
    """Overview of a board: every list and how many cards it holds. Cheap situational
    awareness — call this first instead of dumping all cards. Accepts a board name or slug."""
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        b = await _resolve_board(client, board, cache)
        cards = await _cards(client, b["id"], cache)
        counts = Counter(c["idList"] for c in cards)
        lists = [{"list": l["name"], "cards": counts.get(l["id"], 0)} for l in b.get("lists", [])]
    return {"board": b["name"], "slug": _slug(b["name"]), "lists": lists, "total_cards": len(cards)}


@mcp.tool()
async def get_cards(
    board: str,
    list_name: str | None = None,
    feature: str | None = None,
    label: str | None = None,
    text: str | None = None,
    due_before: str | None = None,
    has_due: bool | None = None,
    include_done: bool = False,
    limit: int = 50,
) -> str:
    """List cards as compact tab-delimited lines: `handle <TAB> title <TAB> list [<TAB> meta...]`.
    meta tokens: `due:YYYY-MM-DD`, `[label1,label2]`, `check:done/total`. Empty fields are omitted.
    All filters are optional and combine with AND — pass only the ones you need:
      list_name     only cards in that list
      feature       only cards in that feature — the precise way to find a card to depend on,
                    once get_state or describe_graph has told you which feature you need
      label         only cards carrying that label
      text          substring match on title or description
      due_before    only cards due strictly before this ISO date
      has_due       True = only cards with a due date, False = only cards without
      include_done  False (default) hides cards labelled `done`
      limit         cap the number of rows (default 50)
    The _meta/STATE card is never returned — use get_state. Use get_card(handle) for one card."""
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        b = await _resolve_board(client, board, cache)
        cards = await _cards(client, b["id"], cache, checklists=True)
        list_name_by_id = {l["id"]: l["name"] for l in b.get("lists", [])}
        meta_ids = {i for i, n in list_name_by_id.items() if n.strip() == META_LIST}
        cards = [c for c in cards if c["idList"] not in meta_ids]

        if not include_done:
            cards = [c for c in cards if not _is_done(c)]
        if feature is not None:
            ft = _slug(feature)
            cards = [c for c in cards
                     if _slug(_parse_meta(c.get("desc"))[2] or list_name_by_id.get(c["idList"], "")) == ft]
        if list_name is not None:
            lst = _resolve_list(b, list_name)
            cards = [c for c in cards if c["idList"] == lst["id"]]
        if label is not None:
            lt = _slug(label)
            cards = [c for c in cards if any(_slug(l["name"]) == lt for l in c.get("labels", []))]
        if text is not None:
            t = text.lower()
            cards = [c for c in cards if t in c["name"].lower() or t in (c.get("desc") or "").lower()]
        if has_due is True:
            cards = [c for c in cards if c.get("due")]
        elif has_due is False:
            cards = [c for c in cards if not c.get("due")]
        if due_before is not None:
            cut = due_before[:10]
            cards = [c for c in cards if c.get("due") and c["due"][:10] < cut]

        cards = cards[:limit]
        handles = _build_handles(cards, _slug(b["name"]), list_name_by_id)

    lines = []
    for c in cards:
        meta = []
        d = _short_due(c.get("due"))
        if d:
            meta.append(f"due:{d}")
        labs = [l["name"] for l in c.get("labels", [])]
        if labs:
            meta.append("[" + ",".join(labs) + "]")
        done, total = _checklist_counts(c)
        if total:
            meta.append(f"check:{done}/{total}")
        cells = [handles[c["id"]], c["name"], list_name_by_id.get(c["idList"], "")] + meta
        lines.append("\t".join(cells))
    return "\n".join(lines) if lines else "No cards match."


@mcp.tool()
async def get_card(handle: str) -> dict:
    """Full detail for one card by handle (board/list/card), including its checklist items.
    Empty fields are omitted from the response."""
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        board, lst, c = await _resolve_handle(client, handle, cache, checklists=True)
        all_cards = await _cards(client, board["id"], cache, checklists=True)
        list_name_by_id = {l["id"]: l["name"] for l in board.get("lists", [])}
        canonical = _build_handles(all_cards, _slug(board["name"]), list_name_by_id)[c["id"]]
        items = [
            {"name": it["name"], "done": it["state"] == "complete"}
            for cl in c.get("checklists", []) for it in cl.get("checkItems", [])
        ]
    return _clean({
        "handle": canonical,
        "title": c["name"],
        "list": lst["name"],
        "description": c.get("desc", ""),
        "due": _short_due(c.get("due")),
        "labels": [l["name"] for l in c.get("labels", [])],
        "checklist": items,
        "url": c.get("shortUrl", ""),
    })


async def _handle_to_link(client, handle: str, cache: dict) -> tuple[str, str]:
    """handle → (shortLink, title). Used to translate LLM-facing handles into stable edges."""
    _, _, c = await _resolve_handle(client, handle, cache)
    return c["shortLink"], c["name"]


@mcp.tool()
async def create_lists(lists: list[NewList]) -> dict:
    """Create one or more lists on a board in a single call (batch).

    Two kinds of list are meaningful here: topic/feature lists (where cards are drafted and
    planned) and day lists named as an ISO date, YYYY-MM-DD (what you actually work from).
    apply_schedule creates day lists on demand, so you rarely need to make those by hand."""
    created, errors = [], []
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        for i, spec in enumerate(lists):
            try:
                b = await _resolve_board(client, spec.board, cache)
                r = await client.post(
                    f"{TRELLO_BASE}/lists",
                    params={**_auth(), "idBoard": b["id"], "name": spec.name, "pos": spec.pos},
                )
                r.raise_for_status()
                cache.pop("boards", None)  # board.lists is now stale
                created.append({"list": f"{_slug(b['name'])}/{_slug(spec.name)}", "name": spec.name})
            except Exception as e:
                errors.append({"index": i, "name": spec.name, "error": str(e)})
    return _clean({"created": created, "errors": errors})


@mcp.tool()
async def create_cards(
    board: str,
    list_name: str,
    cards: list[NewCard],
    feature: str | None = None,
) -> dict:
    """Create a batch of cards in one list, with their dependency edges. The main planning call.

    A card is ONE ATOMIC STEP — something finishable in a single sitting — not a feature. Break a
    feature into steps and create them all in one call. Small cards are the point: they schedule
    cleanly and they beat procrastination. If a step won't fit in a sitting, make it several cards.

      board       board name or slug
      list_name   the feature's topic list (create it first with create_lists)
      feature     feature slug stored on each card; defaults to list_name
      cards       [{title, after?, est?, description?, labels?, due?, checklist?}]

    Edges resolve in a second pass, so cards here may depend on each other in any order. Use a bare
    slug in `after` for a card in this same batch/list, a full handle for one elsewhere.

      create_cards(board="frm", list_name="Authentication", feature="auth", cards=[
        {"title": "User model"},
        {"title": "OAuth callback", "after": ["user-model"]},
        {"title": "Login UI",       "after": ["oauth-callback", "frm/kpi/kpi-model"]}])

    Then call propose_schedule to turn the graph into dates."""
    created, errors = [], []
    async with httpx.AsyncClient() as client:
        cache, label_cache = {}, {}
        b = await _resolve_board(client, board, cache)
        lst = _resolve_list(b, list_name)
        feat = _slug(feature or list_name)
        bs, lsg = _slug(b["name"]), _slug(lst["name"])
        made: list[tuple[NewCard, dict, str]] = []

        for i, spec in enumerate(cards):
            try:
                label_ids = await _label_ids(client, b["id"], spec.labels, label_cache)
                params = {**_auth(), "idList": lst["id"], "name": spec.title, "desc": spec.description}
                if spec.due:
                    params["due"] = spec.due
                if label_ids:
                    params["idLabels"] = ",".join(label_ids)
                r = await client.post(f"{TRELLO_BASE}/cards", params=params)
                r.raise_for_status()
                card = r.json()
                if spec.checklist:
                    await _write_checklist(client, card["id"], spec.checklist)
                handle = f"{bs}/{lsg}/{_slug(spec.title)}"
                made.append((spec, card, handle))
                created.append({"handle": handle, "url": card.get("shortUrl", "")})
            except Exception as e:
                errors.append({"index": i, "title": spec.title, "error": str(e)})

        # Second pass: every card in the batch now exists, so intra-batch edges resolve.
        cache.pop(f"cards:{b['id']}:False", None)
        for spec, card, handle in made:
            try:
                edges = []
                for h in spec.after:
                    ref = h if "/" in h else f"{bs}/{lsg}/{h}"  # bare slug → this list
                    try:
                        edges.append(await _handle_to_link(client, ref, cache))
                    except Exception as e:
                        errors.append({"title": spec.title, "error": f"after '{h}': {e}"})
                desc = _set_meta(spec.description, edges, spec.est, feat)
                r = await client.put(f"{TRELLO_BASE}/cards/{card['id']}", params={**_auth(), "desc": desc})
                r.raise_for_status()
            except Exception as e:
                errors.append({"handle": handle, "error": f"writing deps: {e}"})

    big = [{"card": s.title, "est": s.est} for s, _, _ in made if s.est and s.est > 1]
    out = {"created": created, "errors": errors}
    if big:
        out["too_big"] = big
        out["advice"] = "These declared est>1. Split each into single-sitting steps with split_card."
    return _clean(out)


@mcp.tool()
async def update_cards(updates: list[CardUpdate]) -> dict:
    """Update one or more cards in a single call (batch), each addressed by handle.
    Only the fields you set are changed. `labels` and `checklist` replace wholesale when given.
    `due='null'` clears the due date. Per-card failures are collected in `errors`.

    `after` and `est` edit the dependency graph — see propose_schedule. Setting one leaves the
    other intact; `after=[]` clears the card's dependencies."""
    updated, errors = [], []
    async with httpx.AsyncClient() as client:
        cache, label_cache = {}, {}
        for u in updates:
            try:
                board, lst, c = await _resolve_handle(client, u.handle, cache)
                cid, applied = c["id"], []

                scalar = {**_auth()}
                if u.title is not None:
                    scalar["name"] = u.title
                    applied.append("title")
                if u.description is not None:
                    scalar["desc"] = u.description
                    applied.append("description")

                if u.after is not None or u.est is not None or u.feature is not None:
                    cur_after, cur_est, cur_feat = _parse_meta(c.get("desc"))
                    base = u.description if u.description is not None else c.get("desc", "")
                    if u.after is not None:
                        edges = []
                        for h in u.after:
                            ref = h if "/" in h else f"{_slug(board['name'])}/{_slug(lst['name'])}/{h}"
                            edges.append(await _handle_to_link(client, ref, cache))
                        applied.append("after")
                    else:
                        names = {x["shortLink"]: x["name"] for x in await _cards(client, board["id"], cache)}
                        edges = [(l, names.get(l, "")) for l in cur_after]
                    est = u.est if u.est is not None else cur_est
                    feat = u.feature if u.feature is not None else cur_feat
                    if u.est is not None:
                        applied.append("est")
                    if u.feature is not None:
                        applied.append("feature")
                    scalar["desc"] = _set_meta(base, edges, est, feat)

                if u.due is not None:
                    scalar["due"] = u.due
                    applied.append("due")
                if len(scalar) > 2:
                    r = await client.put(f"{TRELLO_BASE}/cards/{cid}", params=scalar)
                    r.raise_for_status()

                if u.labels is not None:
                    ids = await _label_ids(client, board["id"], u.labels, label_cache)
                    r = await client.put(f"{TRELLO_BASE}/cards/{cid}", params={**_auth(), "idLabels": ",".join(ids)})
                    r.raise_for_status()
                    applied.append("labels")

                if u.checklist is not None:
                    ex = await client.get(f"{TRELLO_BASE}/cards/{cid}/checklists", params=_auth())
                    ex.raise_for_status()
                    for cl in ex.json():
                        await client.delete(f"{TRELLO_BASE}/checklists/{cl['id']}", params=_auth())
                    await _write_checklist(client, cid, u.checklist)
                    applied.append("checklist")

                updated.append({"handle": u.handle, "applied": applied})
            except Exception as e:
                errors.append({"handle": u.handle, "error": str(e)})
    return _clean({"updated": updated, "errors": errors})


@mcp.tool()
async def move_cards(moves: list[CardMove]) -> dict:
    """Move one or more cards to another list in a single call (batch), each addressed by handle.
    Returns the new handle for each moved card. Per-card failures are collected in `errors`."""
    moved, errors = [], []
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        for m in moves:
            try:
                board, _, c = await _resolve_handle(client, m.handle, cache)
                dest = _resolve_list(board, m.to_list)
                r = await client.put(
                    f"{TRELLO_BASE}/cards/{c['id']}",
                    params={**_auth(), "idList": dest["id"], "pos": m.pos},
                )
                r.raise_for_status()
                new_handle = f"{_slug(board['name'])}/{_slug(dest['name'])}/{_slug(c['name'])}"
                moved.append({"from": m.handle, "to": new_handle})
            except Exception as e:
                errors.append({"handle": m.handle, "error": str(e)})
    return _clean({"moved": moved, "errors": errors})


# ── Scheduling ─────────────────────────────────────────────────────────────────

def _schedulable(b: dict, all_cards: list[dict], lists: list[str] | None, today: date) -> list[dict]:
    """Everything not done and not frozen. Frozen = sits in a PAST day list (already worked).
    A card in a past day list WITHOUT the done label is a straggler: it is not frozen, it gets
    pulled forward. The _meta list (STATE card) is never schedulable."""
    name_by_id = {l["id"]: l["name"].strip() for l in b.get("lists", [])}
    ok = []
    for c in all_cards:
        ln = name_by_id.get(c["idList"], "")
        if _slug(ln) == META_LIST.strip("_") or ln == META_LIST or _is_done(c):
            continue
        if lists is not None:
            if _slug(ln) in {_slug(x) for x in lists}:
                ok.append(c)
            continue
        if DATE_RE.match(ln) and date.fromisoformat(ln) >= today:
            ok.append(c)      # future day list — may be re-placed
        elif DATE_RE.match(ln):
            ok.append(c)      # past day list, not done → straggler, pull forward
        else:
            ok.append(c)      # topic list — unscheduled
    return ok


async def _plan(client, board_name: str, deadline: str | None, lists: list[str] | None,
                start: str | None, pace: float):
    """Shared by propose_schedule / apply_schedule so the preview cannot drift from the write."""
    cache: dict = {}
    b = await _resolve_board(client, board_name, cache)
    all_cards = await _cards(client, b["id"], cache)
    today = date.fromisoformat(start) if start else date.today()
    cards = _schedulable(b, all_cards, lists, today)
    return b, cards, _schedule(cards, deadline, start, pace)


def _cycle_report(plan: dict) -> dict:
    names = [plan["by_link"][l]["name"] for l in plan["cycle"] if l in plan["by_link"]]
    return {
        "error": "Dependency cycle — no valid order exists.",
        "cycle": " → ".join(names),
        "fix": (
            "A cycle almost always means a card is too coarse: two cards each need only PART of "
            "the other. Split one of them with split_card so the real dependency runs one way, "
            "then re-run. Do not just delete an edge to silence this."
        ),
    }


@mcp.tool()
async def propose_schedule(
    board: str,
    deadline: str | None = None,
    lists: list[str] | None = None,
    start: str | None = None,
    pace: float = DEFAULT_PACE,
) -> dict:
    """Turn the dependency graph into dates. READ-ONLY — nothing is moved. Show the user the result,
    then call apply_schedule with the SAME arguments.

    Topologically sorts the cards and spreads them evenly across the window, so every day gets a
    roughly equal number of cards and no day is empty. Cards are atomic steps, so this places WHEN
    each is done — nothing spans multiple days.

      board     board name or slug
      deadline  ISO date. Fixed — the schedule compresses to fit and reports `intensity`
                (cards/day) so you can see what you signed up for. OMIT IT to schedule at a
                comfortable `pace` instead and be told the implied end date.
      pace      cards/day when no deadline is given (default 2)
      lists     source lists; default = every topic list + unfinished cards in day lists
      start     ISO date to schedule from; defaults to today

    Excluded automatically: cards labelled `done`, and the _meta/STATE card. Cards left unfinished
    in a PAST day list are stragglers and get pulled forward. Cycles are a hard error."""
    async with httpx.AsyncClient() as client:
        _, _, plan = await _plan(client, board, deadline, lists, start, pace)

    if "error" in plan:
        return {"error": plan["error"]}
    if "cycle" in plan:
        return _cycle_report(plan)

    i = plan["intensity"]
    if deadline:
        verdict = f"{i} cards/day over {plan['window_days']} days to hit {deadline}."
        if i > 3:
            verdict += " That is a lot — consider moving the deadline or cutting scope."
    else:
        verdict = f"At {pace}/day this finishes {plan['end']} ({plan['window_days']} days)."
    if plan["stacked_chain"]:
        verdict += (f" Your longest dependency chain is {plan['chain']} cards but the window is "
                    f"{plan['window_days']} days, so some chained cards share a day.")

    rows = "\n".join(
        f"{r['date']}\t{r['card']}\t{r['feature']}"
        for r in sorted(plan["rows"], key=lambda r: r["date"])
    )
    return _clean({
        "verdict": verdict,
        "intensity": i,
        "window_days": plan["window_days"],
        "end": plan["end"],
        "schedule": rows,
        "load_per_day": plan["load"],
        "dangling": plan["dangling"],
        "too_big": plan["too_big"],
    })


@mcp.tool()
async def apply_schedule(
    board: str,
    deadline: str | None = None,
    lists: list[str] | None = None,
    start: str | None = None,
    pace: float = DEFAULT_PACE,
) -> dict:
    """Place every scheduled card into its day list, in ONE call. Creates missing day lists.

    Recomputes the same plan as propose_schedule (call that first and show the user), then creates
    any missing YYYY-MM-DD lists and moves each card into its date. Cards are moved in topological
    order at pos=bottom, so when several land on the same day their order within that list IS the
    dependency order. Use move_cards afterwards to override any individual placement by hand."""
    async with httpx.AsyncClient() as client:
        b, cards, plan = await _plan(client, board, deadline, lists, start, pace)
        if "error" in plan:
            return {"error": plan["error"]}
        if "cycle" in plan:
            return _cycle_report(plan)

        by_link = plan["by_link"]
        wanted = sorted({r["date"] for r in plan["rows"]})
        existing = {l["name"].strip(): l["id"] for l in b.get("lists", [])}

        made = []
        for d in wanted:
            if d not in existing:
                r = await client.post(
                    f"{TRELLO_BASE}/lists",
                    params={**_auth(), "idBoard": b["id"], "name": d, "pos": "bottom"},
                )
                r.raise_for_status()
                existing[d] = r.json()["id"]
                made.append(d)

        moved, errors = 0, []
        for row in plan["rows"]:  # already topological → pos=bottom yields intra-day order
            try:
                r = await client.put(
                    f"{TRELLO_BASE}/cards/{by_link[row['link']]['id']}",
                    params={**_auth(), "idList": existing[row["date"]], "pos": "bottom"},
                )
                r.raise_for_status()
                moved += 1
            except Exception as e:
                errors.append({"card": row["card"], "error": str(e)})

    return _clean({
        "moved": moved,
        "lists_created": made,
        "intensity": plan["intensity"],
        "end": plan["end"],
        "errors": errors,
    })


@mcp.tool()
async def describe_graph(board: str) -> dict:
    """The planning view: what exists, what is still in flight, and how it depends. START HERE
    before adding a feature — it is the whole context you need, and it is small.

    Finished cards are omitted on purpose: a dependency on done work constrains nothing, so it must
    never be declared. What has already been BUILT is described in the STATE card (get_state), not
    here. So: get_state tells you what you can assume exists; describe_graph tells you what you may
    still need an edge to."""
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        b = await _resolve_board(client, board, cache)
        all_cards = await _cards(client, b["id"], cache)
        name_by_id = {l["id"]: l["name"].strip() for l in b.get("lists", [])}

        live = [c for c in all_cards if not _is_done(c) and name_by_id.get(c["idList"], "") != META_LIST]
        done_n = sum(1 for c in all_cards if _is_done(c))
        handles = _build_handles(live, _slug(b["name"]), name_by_id)
        by_link = {c["shortLink"]: c for c in live}

        feats: dict[str, int] = Counter()
        rows = []
        for c in live:
            after, _, feat = _parse_meta(c.get("desc"))
            feat = feat or _slug(name_by_id.get(c["idList"], ""))
            feats[feat] += 1
            deps = [by_link[a]["name"] for a in after if a in by_link]
            ln = name_by_id.get(c["idList"], "")
            when = ln if DATE_RE.match(ln) else "unscheduled"
            cells = [handles[c["id"]], c["name"], feat, when]
            if deps:
                cells.append("after: " + ", ".join(deps))
            rows.append("\t".join(cells))

    return _clean({
        "features": [{"feature": f, "in_flight": n} for f, n in sorted(feats.items())],
        "done_cards": done_n,
        "in_flight": "\n".join(rows) if rows else "Nothing in flight.",
        "hint": "Depend on these by handle. For what is already built, call get_state.",
    })


@mcp.tool()
async def get_state(board: str) -> dict:
    """Read the STATE card — the board's single source of truth for what the app ACTUALLY IS today:
    what is built and usable, and what is planned but not there yet.

    Read this before planning a feature. It is what lets finished cards be archived without losing
    the knowledge of what they built, so you never have to read back through board history."""
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        b = await _resolve_board(client, board, cache)
        for c in await _cards(client, b["id"], cache):
            if _slug(c["name"]) == _slug(STATE_CARD):
                return {"state": c.get("desc") or "(empty)", "url": c.get("shortUrl", "")}
    return {"state": "(no STATE card yet)", "hint": "Create it with update_state."}


@mcp.tool()
async def update_state(board: str, content: str) -> dict:
    """Overwrite the STATE card (creating it, and the _meta list, if absent).

    `content` REPLACES the card wholesale, so pass the full document — call get_state first and
    edit it, never send a fragment. Keep it the shape of a FLOWS doc: what is built and usable,
    what is in progress, what is planned. Prune anything that stopped being true. This is what
    future planning reads instead of the card history, so if it drifts, the planning drifts.

    Update it whenever a feature ships or the plan changes."""
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        b = await _resolve_board(client, board, cache)
        try:
            lst = _resolve_list(b, META_LIST)
        except ToolError:
            r = await client.post(f"{TRELLO_BASE}/lists",
                                  params={**_auth(), "idBoard": b["id"], "name": META_LIST, "pos": "top"})
            r.raise_for_status()
            lst = r.json()

        for c in await _cards(client, b["id"], cache):
            if _slug(c["name"]) == _slug(STATE_CARD):
                r = await client.put(f"{TRELLO_BASE}/cards/{c['id']}", params={**_auth(), "desc": content})
                r.raise_for_status()
                return {"updated": STATE_CARD, "url": c.get("shortUrl", "")}

        r = await client.post(f"{TRELLO_BASE}/cards",
                              params={**_auth(), "idList": lst["id"], "name": STATE_CARD, "desc": content})
        r.raise_for_status()
    return {"created": STATE_CARD, "url": r.json().get("shortUrl", "")}


class CardSplit(BaseModel):
    handle: str = Field(description="Card to split")
    into: list[str] = Field(description="Titles of the replacement cards, in dependency order")
    chain: bool = Field(default=True, description="True: each part depends on the previous. False: parts are independent.")


@mcp.tool()
async def split_card(splits: list[CardSplit]) -> dict:
    """Break a card into smaller cards, inheriting its dependencies. The repair for a cycle.

    The original's incoming edges attach to the FIRST part and its outgoing edges to the LAST, so
    the surrounding graph stays connected. With chain=True the parts run in sequence. The original
    is archived. Its estimate is divided evenly across the parts — adjust with update_cards if the
    split is lopsided. After splitting, re-point whichever edge caused the cycle at the specific
    part it truly depends on."""
    done, errors = [], []
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        for spec in splits:
            try:
                board, lst, c = await _resolve_handle(client, spec.handle, cache)
                if len(spec.into) < 2:
                    raise ToolError("A split needs at least 2 titles.")
                old_link = c["shortLink"]
                after, est, feat = _parse_meta(c.get("desc"))
                body = META_RE.sub("", c.get("desc") or "").strip()
                each = None  # parts are atomic steps by definition; drop the parent's est

                all_cards = await _cards(client, board["id"], cache)
                names = {x["shortLink"]: x["name"] for x in all_cards}
                inherited = [(l, names.get(l, "")) for l in after]

                new: list[tuple[str, str]] = []
                for idx, title in enumerate(spec.into):
                    if idx == 0:
                        edges = inherited
                    elif spec.chain:
                        edges = [new[-1]]
                    else:
                        edges = inherited
                    r = await client.post(
                        f"{TRELLO_BASE}/cards",
                        params={**_auth(), "idList": lst["id"], "name": title,
                                "desc": _set_meta(body if idx == 0 else "", edges, each, feat), "pos": "bottom"},
                    )
                    r.raise_for_status()
                    nc = r.json()
                    new.append((nc["shortLink"], title))

                # Successors of the original now point at the last part.
                last = new[-1]
                for other in all_cards:
                    o_after, o_est, o_feat = _parse_meta(other.get("desc"))
                    if old_link not in o_after:
                        continue
                    rewired = [(last[0] if l == old_link else l, names.get(l, last[1])) for l in o_after]
                    r = await client.put(
                        f"{TRELLO_BASE}/cards/{other['id']}",
                        params={**_auth(), "desc": _set_meta(other.get("desc", ""), rewired, o_est, o_feat)},
                    )
                    r.raise_for_status()

                await client.put(f"{TRELLO_BASE}/cards/{c['id']}", params={**_auth(), "closed": "true"})
                bs, lsg = _slug(board["name"]), _slug(lst["name"])
                done.append({"split": spec.handle, "into": [f"{bs}/{lsg}/{_slug(t)}" for t in spec.into]})
            except Exception as e:
                errors.append({"handle": spec.handle, "error": str(e)})
    return _clean({"split": done, "errors": errors})


# ── Cron ───────────────────────────────────────────────────────────────────────

def _resolve_cron_board_id() -> str:
    """Returns TRELLO_CRON_BOARD_ID directly, or resolves TRELLO_CRON_BOARD_NAME to an ID via the API."""
    if TRELLO_CRON_BOARD_ID:
        return TRELLO_CRON_BOARD_ID
    if TRELLO_CRON_BOARD_NAME:
        with httpx.Client() as client:
            resp = client.get(f"{TRELLO_BASE}/members/me/boards", params={**_auth(), "fields": "name,id"})
            if resp.status_code == 200:
                for b in resp.json():
                    if b["name"] == TRELLO_CRON_BOARD_NAME:
                        return b["id"]
        print(f"[cron] Board '{TRELLO_CRON_BOARD_NAME}' not found in your Trello boards")
    return ""


def _cron_update_card_statuses() -> None:
    """Labels overdue and due-today cards. Uses TRELLO_CRON_BOARD_ID or resolves TRELLO_CRON_BOARD_NAME. Labels must exist on the board."""
    if not TRELLO_API_KEY or not TRELLO_TOKEN:
        return
    board_id = _resolve_cron_board_id()
    if not board_id:
        return

    now = datetime.now(timezone.utc)
    today = now.date().isoformat()

    with httpx.Client() as client:
        cr = client.get(
            f"{TRELLO_BASE}/boards/{board_id}/cards",
            params={**_auth(), "fields": "name,due,idLabels"},
        )
        if cr.status_code != 200:
            print(f"[cron] Failed to fetch cards: {cr.status_code}")
            return

        lr = client.get(f"{TRELLO_BASE}/boards/{board_id}/labels", params=_auth())
        if lr.status_code != 200:
            return

        name_to_id = {l["name"]: l["id"] for l in lr.json()}
        overdue_id = name_to_id.get("overdue")
        due_today_id = name_to_id.get("due-today")

        for card in cr.json():
            due_str = card.get("due")
            if not due_str:
                continue

            due_dt = datetime.fromisoformat(due_str.replace("Z", "+00:00"))
            current = set(card.get("idLabels", []))
            updated = set(current)

            if due_dt < now:
                if overdue_id:
                    updated.add(overdue_id)
                if due_today_id:
                    updated.discard(due_today_id)
            elif due_dt.date().isoformat() == today:
                if due_today_id:
                    updated.add(due_today_id)
                if overdue_id:
                    updated.discard(overdue_id)

            if updated != current:
                client.put(
                    f"{TRELLO_BASE}/cards/{card['id']}",
                    params={**_auth(), "idLabels": ",".join(updated)},
                )

    print(f"[cron] Card status update done at {now.isoformat()}")


def _cron_loop() -> None:
    while True:
        try:
            _cron_update_card_statuses()
        except Exception as e:
            print(f"[cron] Error: {e}")
        time.sleep(3600)


if __name__ == "__main__":
    threading.Thread(target=_cron_loop, daemon=True).start()
    mcp.run(transport="streamable-http", host="0.0.0.0", port=8091)
