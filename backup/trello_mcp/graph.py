"""Pure graph + scheduling logic. No I/O — operates on card dicts already fetched.

The scheduler: importance-ranked topological sort (a max-priority frontier) → even bucketing
of that order across the window. Precedence is a hard constraint; importance only ranks the
ready frontier. See FLOWS_mcp.md § Scheduling.
"""
import heapq
from collections import defaultdict
from datetime import date, timedelta

from .config import DATE_RE, DEFAULT_EST, DEFAULT_IMPORTANCE, DEFAULT_PACE, META_LIST
from .formatting import _slug
from .meta import _is_done, _is_parked, _parse_meta


def _build_graph(cards: list[dict]) -> tuple[dict, dict, dict, dict, list[dict]]:
    """cards → (by_link, preds, ests, imps, dangling). Edges pointing outside the set (typically to
    an already-done card) are dropped: a dependency on finished work constrains nothing. Edges
    to genuinely unknown refs are reported rather than silently ignored — a missing edge yields
    a confidently wrong schedule, which is worse than no schedule."""
    by_link = {c["shortLink"]: c for c in cards}
    preds: dict[str, list[str]] = {}
    ests: dict[str, float] = {}
    imps: dict[str, int] = {}
    dangling: list[dict] = []
    for c in cards:
        link = c["shortLink"]
        meta = _parse_meta(c.get("desc"))
        after = meta["after"]
        preds[link] = [a for a in after if a in by_link]
        ests[link] = meta["est"] if meta["est"] is not None else DEFAULT_EST
        imps[link] = meta["importance"] if meta["importance"] is not None else DEFAULT_IMPORTANCE
        for a in after:
            if a not in by_link:
                dangling.append({"card": c["name"], "unknown_ref": a})
    return by_link, preds, ests, imps, dangling


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


def _topo(preds: dict[str, list[str]], imps: dict[str, int] | None = None
          ) -> tuple[list[str] | None, dict[str, list[str]]]:
    """Kahn's, but the ready frontier is a MAX-PRIORITY queue on importance rather than FIFO.
    Returns (order, succs); order is None iff a cycle exists.

    Precedence stays hard: only in-degree-0 nodes are ever popped, so a card never precedes its
    prerequisites regardless of importance. Importance only decides which of the CURRENTLY-ready
    cards comes next — as each card is scheduled it unlocks its successors, which then compete in the
    frontier (the 'moving frontier'). Ties break by shortLink for determinism. With imps=None this is
    a plain deterministic topo sort (ties by link).

    The even-bucketing in _schedule then maps this order onto days, so a card ranked earlier here —
    a Must, or a plain blocker of something a Must depends on — lands on an earlier day."""
    succs: dict[str, list[str]] = defaultdict(list)
    indeg = {n: 0 for n in preds}
    for n, ps in preds.items():
        for p in ps:
            succs[p].append(n)
            indeg[n] += 1

    def key(n: str) -> tuple:
        return (-(imps[n] if imps else 0), n)  # higher importance first, then stable by link

    frontier = [n for n, d in indeg.items() if d == 0]
    heapq.heapify(h := [key(n) + (n,) for n in frontier])
    order: list[str] = []
    while h:
        n = heapq.heappop(h)[-1]
        order.append(n)
        for s in succs[n]:
            indeg[s] -= 1
            if indeg[s] == 0:
                heapq.heappush(h, key(s) + (s,))
    return (order if len(order) == len(preds) else None), succs


def _longest_chain(order: list[str], preds: dict[str, list[str]]) -> int:
    """Cards in the longest dependency chain. Informational only — a chain longer than the window
    just means some chained cards share a day, which is allowed."""
    depth: dict[str, int] = {}
    for n in order:
        depth[n] = 1 + max((depth[p] for p in preds[n]), default=0)
    return max(depth.values(), default=0)


def _schedule(cards: list[dict], deadline: str | None = None, start: str | None = None,
              pace: float = DEFAULT_PACE) -> dict:
    """Importance-ranked topological sort, then slice the ordered sequence into even buckets.

    Cards are atomic steps: `est` is a load weight, not a duration, and nothing occupies more than a
    day. Sort so every dependency precedes its dependents (importance ranks the frontier), then walk
    the sequence assigning each card the day at its position in the CUMULATIVE weight. That is
    perfectly even and automatically dependency-safe: a card's day is monotonic in its position, so a
    predecessor always lands on the same day as its dependent or earlier (never later). Cards that
    share a day are still emitted in dependency order, and apply_schedule keeps that order.

    Two modes:
      deadline given → window is fixed; `intensity` (cards/day) reports how hard you are pushing.
      no deadline    → window = enough days at `pace` cards/day; reports the implied end date.
    """
    by_link, preds, ests, imps, dangling = _build_graph(cards)
    if not by_link:
        return {"error": "No cards to schedule."}

    order, _ = _topo(preds, imps)  # importance ranks the ready frontier; precedence stays hard
    if order is None:
        return {"cycle": _find_cycle(preds) or [], "by_link": by_link}

    start_d = date.fromisoformat(start) if start else date.today()
    total = sum(ests.values())
    chain = _longest_chain(order, preds)

    if deadline:
        end_d = date.fromisoformat(deadline)
        window = (end_d - start_d).days + 1
        if window < 1:
            return {"error": f"Deadline {deadline} is not after start {start_d.isoformat()}."}
    else:  # natural pace — the schedule defines its own end
        window = max(chain, int(-(-total // pace)))

    # Even bucketing: a card's day is set by the cumulative weight BEFORE it, mapped onto the window.
    # cum runs 0 → total across the sequence, so day runs 0 → window-1 evenly. Because cum only
    # increases and topo order puts every predecessor first, day is non-decreasing along real edges —
    # the dependency constraint (predecessor day ≤ dependent day, same day allowed) holds for free.
    load: dict[int, float] = defaultdict(float)
    day_of: dict[str, int] = {}
    cum = 0.0
    for n in order:
        day = min(int(cum / total * window), window - 1) if total else 0
        day_of[n] = day
        load[day] += ests[n]
        cum += ests[n]

    rows = [{
        "link": n,
        "card": by_link[n]["name"],
        "feature": _parse_meta(by_link[n].get("desc"))["feature"] or "",
        "date": (start_d + timedelta(days=day_of[n])).isoformat(),
        "est": ests[n],
        "importance": imps[n],
    } for n in order]  # importance-ranked topological order — apply_schedule relies on it

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


def _schedulable(b: dict, all_cards: list[dict], lists: list[str] | None, today: date) -> list[dict]:
    """Everything not done, not parked, and not frozen. Frozen = sits in a PAST day list (already
    worked). A card in a past day list WITHOUT the done label is a straggler: it is not frozen, it
    gets pulled forward. Parked cards are held out until unparked; the _meta list (STATE card) is
    never schedulable."""
    name_by_id = {l["id"]: l["name"].strip() for l in b.get("lists", [])}
    ok = []
    for c in all_cards:
        ln = name_by_id.get(c["idList"], "")
        if _slug(ln) == META_LIST.strip("_") or ln == META_LIST or _is_done(c) or _is_parked(c):
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
