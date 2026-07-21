"""Planning tools: scheduling, the graph/state views, and split_card.

Importing this module registers these tools on the shared `mcp` (via @mcp.tool()).
"""
from collections import Counter

import httpx

from .api import _cards, _handle_to_link, _resolve_board, _resolve_handle, _resolve_list
from .config import (DATE_RE, DEFAULT_IMPORTANCE, DEFAULT_PACE, META_LIST, STATE_CARD,
                     TRELLO_BASE, ToolError, _auth, mcp)
from .formatting import _build_handles, _clean, _slug
from .graph import _cycle_report, _schedulable, _schedule
from .meta import META_RE, _is_done, _is_parked, _parse_meta, _set_meta
from .models import CardSplit


async def _plan(client, board_name: str, deadline: str | None, lists: list[str] | None,
                start: str | None, pace: float):
    """Shared by propose_schedule / apply_schedule so the preview cannot drift from the write."""
    from datetime import date

    cache: dict = {}
    b = await _resolve_board(client, board_name, cache)
    all_cards = await _cards(client, b["id"], cache)
    today = date.fromisoformat(start) if start else date.today()
    cards = _schedulable(b, all_cards, lists, today)
    return b, cards, _schedule(cards, deadline, start, pace)


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
    each is done — nothing spans multiple days. Importance (MoSCoW 1-3) ranks which of the currently
    unblocked cards land earlier; it never overrides a dependency.

      board     board name or slug
      deadline  ISO date. Fixed — the schedule compresses to fit and reports `intensity`
                (cards/day) so you can see what you signed up for. OMIT IT to schedule at a
                comfortable `pace` instead and be told the implied end date.
      pace      cards/day when no deadline is given (default 2)
      lists     source lists; default = every topic list + unfinished cards in day lists
      start     ISO date to schedule from; defaults to today

    Excluded automatically: cards labelled `done` or `parked`, and the _meta/STATE card. Cards left
    unfinished in a PAST day list are stragglers and get pulled forward. Cycles are a hard error."""
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
        f"{r['date']}\t{r['card']}\t{r['feature']}\timp:{r['importance']}"
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

        # UX: keep day lists on the LEFT, chronological, ahead of the feature/topic lists — so the
        # day you work from is never a scroll away. Repositioning every day list (existing + new)
        # in REVERSE date order at pos=top makes the earliest date end up leftmost (a stack: the
        # last push lands on top). Feature lists keep their relative order on the right.
        for d in sorted((n for n in existing if DATE_RE.match(n)), reverse=True):
            r = await client.put(f"{TRELLO_BASE}/lists/{existing[d]}", params={**_auth(), "pos": "top"})
            r.raise_for_status()

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

        live = [c for c in all_cards if not _is_done(c) and not _is_parked(c)
                and name_by_id.get(c["idList"], "") != META_LIST]
        done_n = sum(1 for c in all_cards if _is_done(c))
        parked_n = sum(1 for c in all_cards if _is_parked(c))
        handles = _build_handles(live, _slug(b["name"]), name_by_id)
        by_link = {c["shortLink"]: c for c in live}

        feats: dict[str, int] = Counter()
        rows = []
        for c in live:
            pm = _parse_meta(c.get("desc"))
            feat = pm["feature"] or _slug(name_by_id.get(c["idList"], ""))
            feats[feat] += 1
            imp = pm["importance"] if pm["importance"] is not None else DEFAULT_IMPORTANCE
            deps = [by_link[a]["name"] for a in pm["after"] if a in by_link]
            ln = name_by_id.get(c["idList"], "")
            when = ln if DATE_RE.match(ln) else "unscheduled"
            cells = [handles[c["id"]], c["name"], feat, f"imp:{imp}", when]
            if deps:
                cells.append("after: " + ", ".join(deps))
            rows.append("\t".join(cells))

    return _clean({
        "features": [{"feature": f, "in_flight": n} for f, n in sorted(feats.items())],
        "done_cards": done_n,
        "parked_cards": parked_n,
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


@mcp.tool()
async def split_card(splits: list[CardSplit]) -> dict:
    """Break a card into smaller cards, inheriting its dependencies. The repair for a cycle.

    The original's incoming edges attach to the FIRST part and its outgoing edges to the LAST, so
    the surrounding graph stays connected. With chain=True the parts run in sequence. The original
    is archived. Parts inherit the original's feature and importance; their est is dropped (parts
    are atomic by definition). After splitting, re-point whichever edge caused the cycle at the
    specific part it truly depends on."""
    done, errors = [], []
    async with httpx.AsyncClient() as client:
        cache: dict = {}
        for spec in splits:
            try:
                board, lst, c = await _resolve_handle(client, spec.handle, cache)
                if len(spec.into) < 2:
                    raise ToolError("A split needs at least 2 titles.")
                old_link = c["shortLink"]
                pm = _parse_meta(c.get("desc"))
                feat, imp = pm["feature"], pm["importance"]  # parts inherit feature + importance
                body = META_RE.sub("", c.get("desc") or "").strip()
                each = None  # parts are atomic steps by definition; drop the parent's est

                all_cards = await _cards(client, board["id"], cache)
                names = {x["shortLink"]: x["name"] for x in all_cards}
                inherited = [(l, names.get(l, "")) for l in pm["after"]]

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
                                "desc": _set_meta(body if idx == 0 else "", edges, each, feat, imp), "pos": "bottom"},
                    )
                    r.raise_for_status()
                    nc = r.json()
                    new.append((nc["shortLink"], title))

                # Successors of the original now point at the last part.
                last = new[-1]
                for other in all_cards:
                    om = _parse_meta(other.get("desc"))
                    if old_link not in om["after"]:
                        continue
                    rewired = [(last[0] if l == old_link else l, names.get(l, last[1])) for l in om["after"]]
                    r = await client.put(
                        f"{TRELLO_BASE}/cards/{other['id']}",
                        params={**_auth(), "desc": _set_meta(other.get("desc", ""), rewired, om["est"], om["feature"], om["importance"])},
                    )
                    r.raise_for_status()

                await client.put(f"{TRELLO_BASE}/cards/{c['id']}", params={**_auth(), "closed": "true"})
                bs, lsg = _slug(board["name"]), _slug(lst["name"])
                done.append({"split": spec.handle, "into": [f"{bs}/{lsg}/{_slug(t)}" for t in spec.into]})
            except Exception as e:
                errors.append({"handle": spec.handle, "error": str(e)})
    return _clean({"split": done, "errors": errors})
