"""Card and list tools: read, create, update, move, and the done/parked/archive state toggles.

Importing this module registers these tools on the shared `mcp` (via @mcp.tool()).
"""
import httpx

from .api import (_cards, _ensure_label, _handle_to_link, _label_ids, _resolve_board,
                  _resolve_handle, _resolve_list, _write_checklist)
from .config import DEFAULT_IMPORTANCE, DONE_LABEL, META_LIST, PARKED_LABEL, TRELLO_BASE, _auth, mcp
from .formatting import _build_handles, _checklist_counts, _clean, _short_due, _slug
from .meta import _is_done, _is_parked, _parse_meta, _set_meta
from .models import CardMove, CardUpdate, NewCard, NewList


@mcp.tool()
async def describe_board(board: str) -> dict:
    """Overview of a board: every list with its card count split into open / done / parked. Cheap
    situational awareness — call this first instead of dumping all cards. Accepts a board name or
    slug. `open` is what is still live work; use describe_graph to see how those cards depend."""
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        b = await _resolve_board(client, board, cache)
        cards = await _cards(client, b["id"], cache)
        by_list: dict[str, dict] = {l["id"]: {"done": 0, "parked": 0, "open": 0} for l in b.get("lists", [])}
        for c in cards:
            bucket = by_list.get(c["idList"])
            if bucket is None:
                continue
            bucket["done" if _is_done(c) else "parked" if _is_parked(c) else "open"] += 1
        lists = [{"list": l["name"], **{k: v for k, v in by_list[l["id"]].items() if v}}
                 for l in b.get("lists", [])]
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
    meta tokens: `imp:N`, `due:YYYY-MM-DD`, `[label1,label2]`, `check:done/total`. Empty fields are
    omitted. All filters are optional and combine with AND — pass only the ones you need:
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
                     if _slug(_parse_meta(c.get("desc"))["feature"] or list_name_by_id.get(c["idList"], "")) == ft]
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
        pm = _parse_meta(c.get("desc"))
        imp = pm["importance"] if pm["importance"] is not None else DEFAULT_IMPORTANCE
        meta.append(f"imp:{imp}")
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
      cards       [{title, after?, est?, importance?, description?, labels?, due?, checklist?}]

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
                label_ids, skipped = await _label_ids(client, b["id"], spec.labels, label_cache)
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
                entry = {"handle": handle, "url": card.get("shortUrl", "")}
                if skipped:
                    entry["labels_skipped"] = skipped
                created.append(entry)
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
                desc = _set_meta(spec.description, edges, spec.est, feat, spec.importance)
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

    `after`, `est`, `feature`, and `importance` edit the planning meta — see propose_schedule.
    Setting one leaves the others intact; `after=[]` clears the card's dependencies."""
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

                if u.after is not None or u.est is not None or u.feature is not None or u.importance is not None:
                    cur = _parse_meta(c.get("desc"))
                    base = u.description if u.description is not None else c.get("desc", "")
                    if u.after is not None:
                        edges = []
                        for h in u.after:
                            ref = h if "/" in h else f"{_slug(board['name'])}/{_slug(lst['name'])}/{h}"
                            edges.append(await _handle_to_link(client, ref, cache))
                        applied.append("after")
                    else:
                        names = {x["shortLink"]: x["name"] for x in await _cards(client, board["id"], cache)}
                        edges = [(l, names.get(l, "")) for l in cur["after"]]
                    est = u.est if u.est is not None else cur["est"]
                    feat = u.feature if u.feature is not None else cur["feature"]
                    imp = u.importance if u.importance is not None else cur["importance"]
                    if u.est is not None:
                        applied.append("est")
                    if u.feature is not None:
                        applied.append("feature")
                    if u.importance is not None:
                        applied.append("importance")
                    scalar["desc"] = _set_meta(base, edges, est, feat, imp)

                if u.due is not None:
                    scalar["due"] = u.due
                    applied.append("due")
                if len(scalar) > 2:
                    r = await client.put(f"{TRELLO_BASE}/cards/{cid}", params=scalar)
                    r.raise_for_status()

                skipped_labels = []
                if u.labels is not None:
                    ids, skipped_labels = await _label_ids(client, board["id"], u.labels, label_cache)
                    r = await client.put(f"{TRELLO_BASE}/cards/{cid}", params={**_auth(), "idLabels": ",".join(ids)})
                    r.raise_for_status()
                    # Report only what actually stuck. A name that isn't a board label is dropped by
                    # Trello, so claiming applied:["labels"] for it would be a false success.
                    if ids:
                        applied.append("labels")

                if u.checklist is not None:
                    ex = await client.get(f"{TRELLO_BASE}/cards/{cid}/checklists", params=_auth())
                    ex.raise_for_status()
                    for cl in ex.json():
                        await client.delete(f"{TRELLO_BASE}/checklists/{cl['id']}", params=_auth())
                    await _write_checklist(client, cid, u.checklist)
                    applied.append("checklist")

                entry = {"handle": u.handle, "applied": applied}
                if skipped_labels:
                    entry["labels_skipped"] = skipped_labels
                    entry["hint"] = ("These are not labels on the board and were dropped. To mark a "
                                     "card done use complete_cards, which creates the label as needed.")
                updated.append(entry)
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


async def _toggle_label(handles: list[str], label: str, on: bool, key: str) -> dict:
    """Add (on=True) or remove a board label across a batch of cards, creating it if needed. Shared
    by complete_cards and park_cards — both are just 'this card carries label X or not'."""
    changed, errors = [], []
    async with httpx.AsyncClient() as client:
        cache, label_cache = {}, {}
        for h in handles:
            try:
                board, _, c = await _resolve_handle(client, h, cache)
                lid = await _ensure_label(client, board["id"], label, label_cache)
                have = {l["id"] for l in c.get("labels", [])}
                want = have | {lid} if on else have - {lid}
                if want != have:
                    r = await client.put(f"{TRELLO_BASE}/cards/{c['id']}",
                                         params={**_auth(), "idLabels": ",".join(want)})
                    r.raise_for_status()
                changed.append({"handle": h, key: on})
            except Exception as e:
                errors.append({"handle": h, "error": str(e)})
    return _clean({"changed": changed, "errors": errors})


@mcp.tool()
async def complete_cards(handles: list[str], done: bool = True) -> dict:
    """Mark one or more cards done (or reopen them with done=False). Batch, addressed by handle.

    This is the ONLY correct way to tick a card. The whole planning system keys off a label literally
    named `done` — get_state, describe_graph, and the scheduler all treat a card as finished iff it
    carries that label. This tool creates the `done` label on the board the first time it is needed,
    so it always sticks; setting the label by hand via update_cards silently fails if the label does
    not already exist. Done cards drop out of scheduling and out of the dependency-candidate set."""
    return await _toggle_label(handles, DONE_LABEL, done, "done")


@mcp.tool()
async def park_cards(handles: list[str], parked: bool = True) -> dict:
    """Park one or more cards (or unpark with parked=False). Batch, addressed by handle.

    Parked is 'not now', distinct from done ('finished'). A parked card is held out of scheduling
    and out of describe_graph, but is NOT complete — use this for work you are deferring, or to keep
    a list out of the plan without passing `lists=` on every schedule call. Unpark to bring it back.
    Creates the `parked` label on the board as needed."""
    return await _toggle_label(handles, PARKED_LABEL, parked, "parked")


@mcp.tool()
async def archive_cards(handles: list[str]) -> dict:
    """Archive (close) one or more cards, removing them from the board without deleting them. Batch,
    addressed by handle. Use this for template junk and anything you want out of sight — it is the
    right tool for 'hide this', which done and parked are not. Archived cards can be restored from
    Trello's UI. This does not delete; there is no hard-delete tool by design."""
    archived, errors = [], []
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        for h in handles:
            try:
                _, _, c = await _resolve_handle(client, h, cache)
                r = await client.put(f"{TRELLO_BASE}/cards/{c['id']}", params={**_auth(), "closed": "true"})
                r.raise_for_status()
                archived.append({"handle": h})
            except Exception as e:
                errors.append({"handle": h, "error": str(e)})
    return _clean({"archived": archived, "errors": errors})
