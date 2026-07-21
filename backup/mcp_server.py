"""Entrypoint for the HabitTracker Trello MCP server.

The implementation lives in the `trello_mcp` package (see trello_mcp/__init__.py and
FLOWS_mcp.md). This module:
  1. re-exports every public name so `import mcp_server` and the test-suite keep working,
  2. keeps the hourly cron here (the tests set mcp_server.TRELLO_CRON_BOARD_* directly, so
     the cron must read those from this module's namespace),
  3. runs the server.

`import httpx` below is load-bearing: the tests patch `mcp_server.httpx.AsyncClient` /
`.Client`. Because `import httpx` returns the one shared module object, patching it here also
patches the httpx used inside every trello_mcp submodule.
"""
import threading
import time
from collections import Counter
from datetime import datetime, timezone

import httpx

from trello_mcp.config import (DATE_RE, DEFAULT_EST, DEFAULT_IMPORTANCE, DEFAULT_PACE,
                               DONE_LABEL, IMPORTANCE_MAX, IMPORTANCE_MIN, META_LIST, PARKED_LABEL,
                               STATE_CARD, TRELLO_API_KEY, TRELLO_BASE, TRELLO_CRON_BOARD_ID,
                               TRELLO_CRON_BOARD_NAME, TRELLO_TOKEN, ToolError, _auth, mcp)
from trello_mcp.formatting import (_build_handles, _checklist_counts, _clean, _fmt_num,
                                   _short_due, _slug)
from trello_mcp.meta import (META_RE, _has_label, _is_done, _is_parked, _parse_meta,
                             _render_meta, _set_meta)
from trello_mcp.api import (_board_labels, _boards, _cards, _ensure_label, _get, _handle_to_link,
                            _label_ids, _resolve_board, _resolve_handle, _resolve_list,
                            _write_checklist)
from trello_mcp.graph import (_build_graph, _cycle_report, _find_cycle, _longest_chain,
                              _schedulable, _schedule, _topo)
from trello_mcp.models import CardMove, CardSplit, CardUpdate, NewCard, NewList
from trello_mcp.tools_cards import (_toggle_label, archive_cards, complete_cards, create_cards,
                                    create_lists, describe_board, get_card, get_cards, move_cards,
                                    park_cards, update_cards)
from trello_mcp.tools_planning import (_plan, apply_schedule, describe_graph, get_state,
                                       propose_schedule, split_card, update_state)


# ── Cron: hourly overdue / due-today labelling ────────────────────────────────────
# Kept in this module (not a submodule) on purpose: the tests set
# mcp_server.TRELLO_CRON_BOARD_ID / _NAME directly, so the cron must read them from here.

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


def _cron_archive_empty_day_lists() -> None:
    """Archive open day lists (name matches YYYY-MM-DD) that hold no cards. A day list is a
    scheduling artifact — once its cards are done or moved it is empty clutter, so it gets closed
    (reversible from the Trello UI). Only DATE-named lists are touched: feature/topic lists and the
    _meta list are left alone even when empty, since an empty topic list is usually intentional."""
    if not TRELLO_API_KEY or not TRELLO_TOKEN:
        return
    board_id = _resolve_cron_board_id()
    if not board_id:
        return

    with httpx.Client() as client:
        lr = client.get(f"{TRELLO_BASE}/boards/{board_id}/lists", params={**_auth(), "fields": "name"})
        cr = client.get(f"{TRELLO_BASE}/boards/{board_id}/cards", params={**_auth(), "fields": "idList"})
        if lr.status_code != 200 or cr.status_code != 200:
            return

        counts = Counter(c["idList"] for c in cr.json())
        archived = 0
        for l in lr.json():
            if DATE_RE.match(l["name"].strip()) and counts.get(l["id"], 0) == 0:
                client.put(f"{TRELLO_BASE}/lists/{l['id']}/closed", params={**_auth(), "value": "true"})
                archived += 1
        if archived:
            print(f"[cron] Archived {archived} empty day list(s)")


def _cron_loop() -> None:
    while True:
        try:
            _cron_update_card_statuses()
        except Exception as e:
            print(f"[cron] Error: {e}")
        try:
            _cron_archive_empty_day_lists()
        except Exception as e:
            print(f"[cron] Error (list cleanup): {e}")
        time.sleep(3600)


if __name__ == "__main__":
    threading.Thread(target=_cron_loop, daemon=True).start()
    mcp.run(transport="streamable-http", host="0.0.0.0", port=8091)
