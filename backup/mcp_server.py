import difflib
import os
import re
import threading
import time
from collections import Counter
from datetime import datetime, timezone

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
        params = {"fields": "name,desc,due,idList,labels,shortUrl"}
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


async def _write_checklist(client, card_id: str, items: list[str]) -> None:
    r = await client.post(f"{TRELLO_BASE}/checklists", params={**_auth(), "idCard": card_id, "name": "Tasks"})
    r.raise_for_status()
    cl_id = r.json()["id"]
    for item in items:
        await client.post(f"{TRELLO_BASE}/checklists/{cl_id}/checkItems", params={**_auth(), "name": item})


# ── Input models (typed so the LLM sees a clear schema with sensible defaults) ────

class NewCard(BaseModel):
    board: str = Field(description="Board name or slug")
    list_name: str = Field(description="List name or slug the card goes into")
    title: str
    description: str = ""
    labels: list[str] = Field(default_factory=list, description="Existing label names on the board; unknown names are skipped")
    due: str | None = Field(default=None, description="ISO 8601 due date, or omit for none")
    checklist: list[str] = Field(default_factory=list)


class CardUpdate(BaseModel):
    handle: str
    title: str | None = None
    description: str | None = None
    labels: list[str] | None = Field(default=None, description="Replaces all labels when given")
    due: str | None = Field(default=None, description="ISO 8601 to set, the string 'null' to clear, omit to leave unchanged")
    checklist: list[str] | None = Field(default=None, description="Replaces the checklist when given")


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
    label: str | None = None,
    text: str | None = None,
    due_before: str | None = None,
    has_due: bool | None = None,
    limit: int = 50,
) -> str:
    """List cards as compact tab-delimited lines: `handle <TAB> title <TAB> list [<TAB> meta...]`.
    meta tokens: `due:YYYY-MM-DD`, `[label1,label2]`, `check:done/total`. Empty fields are omitted.
    All filters are optional and combine with AND — pass only the ones you need:
      list_name   only cards in that list
      label       only cards carrying that label
      text        substring match on title or description
      due_before  only cards due strictly before this ISO date
      has_due     True = only cards with a due date, False = only cards without
      limit       cap the number of rows (default 50)
    Use get_card(handle) for full detail on a single card."""
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        b = await _resolve_board(client, board, cache)
        cards = await _cards(client, b["id"], cache, checklists=True)
        list_name_by_id = {l["id"]: l["name"] for l in b.get("lists", [])}

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


@mcp.tool()
async def create_cards(cards: list[NewCard]) -> dict:
    """Create one or more cards in a single call (batch). Returns the new handle + url for each.
    Per-card failures are collected in `errors` without aborting the rest of the batch."""
    created, errors = [], []
    async with httpx.AsyncClient() as client:
        cache, label_cache = {}, {}
        for i, spec in enumerate(cards):
            try:
                b = await _resolve_board(client, spec.board, cache)
                lst = _resolve_list(b, spec.list_name)
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
                handle = f"{_slug(b['name'])}/{_slug(lst['name'])}/{_slug(spec.title)}"
                created.append({"handle": handle, "url": card.get("shortUrl", "")})
            except Exception as e:
                errors.append({"index": i, "title": spec.title, "error": str(e)})
    return _clean({"created": created, "errors": errors})


@mcp.tool()
async def update_cards(updates: list[CardUpdate]) -> dict:
    """Update one or more cards in a single call (batch), each addressed by handle.
    Only the fields you set are changed. `labels` and `checklist` replace wholesale when given.
    `due='null'` clears the due date. Per-card failures are collected in `errors`."""
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
