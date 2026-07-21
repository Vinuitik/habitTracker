"""Live Trello resolution and writes. Per-call caches only — no persistent state.

Every resolver takes a `cache` dict scoped to one tool call. Trello is the single source of
truth, so there is nothing to invalidate between calls. Resolution teaches on a miss (difflib
"did you mean …?").
"""
import difflib
from collections import Counter

import httpx

from .config import DATE_RE, TRELLO_BASE, ToolError, _auth
from .formatting import _slug


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


async def _board_labels(client, board_id: str, cache: dict) -> dict:
    if board_id not in cache:
        r = await client.get(f"{TRELLO_BASE}/boards/{board_id}/labels", params=_auth())
        r.raise_for_status()
        cache[board_id] = {_slug(l["name"]): l["id"] for l in r.json() if l["name"]}
    return cache[board_id]


async def _label_ids(client, board_id: str, names: list[str], cache: dict) -> tuple[list[str], list[str]]:
    """Returns (ids, skipped). Unknown label names are NOT created — they are reported so the
    caller never claims success for a label that silently vanished."""
    if not names:
        return [], []
    m = await _board_labels(client, board_id, cache)
    ids = [m[_slug(n)] for n in names if _slug(n) in m]
    skipped = [n for n in names if _slug(n) not in m]
    return ids, skipped


async def _ensure_label(client, board_id: str, name: str, cache: dict) -> str:
    """Resolve a label id by name, creating the label on the board if it does not exist.
    Used for the `done` label the completion system depends on — it must exist to be applied."""
    m = await _board_labels(client, board_id, cache)
    if _slug(name) in m:
        return m[_slug(name)]
    r = await client.post(f"{TRELLO_BASE}/labels",
                          params={**_auth(), "idBoard": board_id, "name": name, "color": "green"})
    r.raise_for_status()
    lid = r.json()["id"]
    m[_slug(name)] = lid  # keep the per-call cache coherent
    return lid


async def _write_checklist(client, card_id: str, items: list[str]) -> None:
    r = await client.post(f"{TRELLO_BASE}/checklists", params={**_auth(), "idCard": card_id, "name": "Tasks"})
    r.raise_for_status()
    cl_id = r.json()["id"]
    for item in items:
        await client.post(f"{TRELLO_BASE}/checklists/{cl_id}/checkItems", params={**_auth(), "name": item})


async def _handle_to_link(client, handle: str, cache: dict) -> tuple[str, str]:
    """handle → (shortLink, title). Used to translate LLM-facing handles into stable edges."""
    _, _, c = await _resolve_handle(client, handle, cache)
    return c["shortLink"], c["name"]


async def _archive_empty_day_lists(client, board_id: str) -> list[str]:
    """Archive open day lists (name YYYY-MM-DD) that now hold no cards. Active cleanup, called at the
    end of write ops that shuffle cards around (apply_schedule / move / archive / split); the cron
    does the same passively. Fetches fresh — the caller's per-call cache is stale after the writes.
    Only DATE-named lists are touched (feature/topic lists and _meta are left alone). Returns the
    archived list names."""
    lr = await client.get(f"{TRELLO_BASE}/boards/{board_id}/lists", params={**_auth(), "fields": "name"})
    lr.raise_for_status()
    cr = await client.get(f"{TRELLO_BASE}/boards/{board_id}/cards", params={**_auth(), "fields": "idList"})
    cr.raise_for_status()
    counts = Counter(c["idList"] for c in cr.json())
    archived = []
    for l in lr.json():
        name = l["name"].strip()
        if DATE_RE.match(name) and counts.get(l["id"], 0) == 0:
            r = await client.put(f"{TRELLO_BASE}/lists/{l['id']}/closed", params={**_auth(), "value": "true"})
            r.raise_for_status()
            archived.append(name)
    return archived
