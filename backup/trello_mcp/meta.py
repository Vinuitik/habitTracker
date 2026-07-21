"""The ```meta block stored in card descriptions, and the label predicates.

Dependencies and estimates live in a fenced ```meta block in the card description. Edges
reference Trello's shortLink — permanent, and crucially stable across BOTH renames and list
moves. Handles are `board/list/card`, so a card moving from a topic list into a dated list
changes its handle; an edge stored as a handle would dangle on the single most common action
in this workflow. The `# comment` after each link keeps the block readable in the Trello UI.
The LLM only ever sees handles — we translate.
"""
import re

from .config import DONE_LABEL, IMPORTANCE_MAX, IMPORTANCE_MIN, PARKED_LABEL
from .formatting import _fmt_num, _slug

META_RE = re.compile(r"```meta\s*\n(.*?)\n?```", re.S)


def _parse_meta(desc: str) -> dict:
    """desc → {after:[shortLink], est, feature, importance}. Missing keys are None (after is []).
    Absent values are NOT defaulted here — callers apply DEFAULT_EST / DEFAULT_IMPORTANCE at use, so
    a card without the line stays clean on disk and only gets one written when explicitly set."""
    out: dict = {"after": [], "est": None, "feature": None, "importance": None}
    m = META_RE.search(desc or "")
    if not m:
        return out
    for raw in m.group(1).splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        k, _, v = line.partition(":")
        k, v = k.strip().lower(), v.strip()
        if k == "after" and v:
            out["after"].extend(p.strip() for p in v.split(",") if p.strip())
        elif k == "est" and v:
            try:
                out["est"] = float(v)
            except ValueError:
                pass
        elif k == "feature" and v:
            out["feature"] = v
        elif k == "importance" and v:
            try:
                out["importance"] = max(IMPORTANCE_MIN, min(IMPORTANCE_MAX, int(float(v))))
            except ValueError:
                pass
    return out


def _render_meta(after: list[tuple[str, str]], est: float | None,
                 feature: str | None, importance: int | None = None) -> str:
    lines = [f"after: {link}  # {title}" for link, title in after]
    if feature:
        lines.append(f"feature: {feature}")
    if importance is not None:
        lines.append(f"importance: {importance}")
    if est is not None:
        lines.append(f"est: {_fmt_num(est)}")
    return "```meta\n" + "\n".join(lines) + "\n```" if lines else ""


def _set_meta(desc: str, after: list[tuple[str, str]], est: float | None,
              feature: str | None = None, importance: int | None = None) -> str:
    """Replace the meta block in desc, leaving the human prose untouched."""
    body = META_RE.sub("", desc or "").strip()
    block = _render_meta(after, est, feature, importance)
    return f"{body}\n\n{block}".strip() if block else body


def _has_label(card: dict, name: str) -> bool:
    return any(_slug(l.get("name", "")) == name for l in card.get("labels", []))


def _is_done(card: dict) -> bool:
    return _has_label(card, DONE_LABEL)


def _is_parked(card: dict) -> bool:
    return _has_label(card, PARKED_LABEL)
