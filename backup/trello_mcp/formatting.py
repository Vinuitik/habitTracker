"""Pure string / dict helpers. No I/O, no package imports beyond stdlib.

A handle is `board/list/card` in kebab-case, e.g. "frm/auth/google-sso". Handles are NOT
stored — they are derived from live Trello names on every call and resolved back to Trello
IDs server-side. Trello is the single source of truth; there is no mirror to drift. On a
name collision within one list we append a deterministic tiebreak from the real card id
(`~a1b2`), so a handle always points at one card.
"""
import re
from collections import Counter


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


def _fmt_num(x: float) -> str:
    return str(int(x)) if float(x).is_integer() else f"{x:g}"
