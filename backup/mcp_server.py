import os
import threading
import time
from datetime import datetime, timezone

import httpx
from fastmcp import FastMCP

TRELLO_API_KEY = os.getenv("TRELLO_API_KEY", "")
TRELLO_TOKEN = os.getenv("TRELLO_TOKEN", "")
TRELLO_CRON_BOARD_ID = os.getenv("TRELLO_CRON_BOARD_ID", "")
TRELLO_CRON_BOARD_NAME = os.getenv("TRELLO_CRON_BOARD_NAME", "")
TRELLO_BASE = "https://api.trello.com/1"

mcp = FastMCP("HabitTracker Trello")


def _auth() -> dict:
    return {"key": TRELLO_API_KEY, "token": TRELLO_TOKEN}


# ── Tools ──────────────────────────────────────────────────────────────────────

@mcp.tool()
async def list_boards_and_lists() -> dict:
    """Returns all Trello boards the authenticated user has access to, along with the lists inside each board. Call this first to resolve names to IDs."""
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{TRELLO_BASE}/members/me/boards",
            params={**_auth(), "fields": "name,id", "lists": "open"},
        )
        resp.raise_for_status()

    return {
        "boards": [
            {
                "board_id": b["id"],
                "board_name": b["name"],
                "lists": [{"list_id": l["id"], "list_name": l["name"]} for l in b.get("lists", [])],
            }
            for b in resp.json()
        ]
    }


@mcp.tool()
async def create_card(
    list_id: str,
    title: str,
    description: str,
    checklist_items: list[str] | None = None,
    labels: list[str] | None = None,
    due_date: str | None = None,
) -> dict:
    """Creates a new Trello card in the specified list. Call list_boards_and_lists first to resolve list_id."""
    async with httpx.AsyncClient() as client:
        lr = await client.get(f"{TRELLO_BASE}/lists/{list_id}", params={**_auth(), "fields": "idBoard"})
        lr.raise_for_status()
        board_id = lr.json()["idBoard"]

        label_ids: list[str] = []
        if labels:
            lbr = await client.get(f"{TRELLO_BASE}/boards/{board_id}/labels", params=_auth())
            lbr.raise_for_status()
            name_to_id = {l["name"]: l["id"] for l in lbr.json()}
            label_ids = [name_to_id[n] for n in labels if n in name_to_id]

        card_params: dict = {**_auth(), "idList": list_id, "name": title, "desc": description}
        if due_date:
            card_params["due"] = due_date
        if label_ids:
            card_params["idLabels"] = ",".join(label_ids)

        cr = await client.post(f"{TRELLO_BASE}/cards", params=card_params)
        cr.raise_for_status()
        card = cr.json()
        card_id = card["id"]

        if checklist_items:
            clr = await client.post(
                f"{TRELLO_BASE}/checklists",
                params={**_auth(), "idCard": card_id, "name": "Tasks"},
            )
            clr.raise_for_status()
            cl_id = clr.json()["id"]
            for item in checklist_items:
                await client.post(
                    f"{TRELLO_BASE}/checklists/{cl_id}/checkItems",
                    params={**_auth(), "name": item},
                )

    return {"card_id": card_id, "card_url": card.get("shortUrl", ""), "title": title}


@mcp.tool()
async def update_card(card_id: str, fields: dict) -> dict:
    """Updates fields on an existing Trello card. Pass only the fields to change."""
    async with httpx.AsyncClient() as client:
        scalar: dict = {**_auth()}
        if "title" in fields:
            scalar["name"] = fields["title"]
        if "description" in fields:
            scalar["desc"] = fields["description"]
        if "due_date" in fields:
            scalar["due"] = "null" if fields["due_date"] is None else fields["due_date"]
        if "list_id" in fields:
            scalar["idList"] = fields["list_id"]

        if len(scalar) > 2:
            r = await client.put(f"{TRELLO_BASE}/cards/{card_id}", params=scalar)
            r.raise_for_status()

        if "labels" in fields:
            cr = await client.get(f"{TRELLO_BASE}/cards/{card_id}", params={**_auth(), "fields": "idBoard"})
            cr.raise_for_status()
            board_id = cr.json()["idBoard"]
            lbr = await client.get(f"{TRELLO_BASE}/boards/{board_id}/labels", params=_auth())
            lbr.raise_for_status()
            name_to_id = {l["name"]: l["id"] for l in lbr.json()}
            ids = [name_to_id[n] for n in fields["labels"] if n in name_to_id]
            r = await client.put(f"{TRELLO_BASE}/cards/{card_id}", params={**_auth(), "idLabels": ",".join(ids)})
            r.raise_for_status()

        if "checklist_items" in fields:
            existing = await client.get(f"{TRELLO_BASE}/cards/{card_id}/checklists", params=_auth())
            existing.raise_for_status()
            for cl in existing.json():
                await client.delete(f"{TRELLO_BASE}/checklists/{cl['id']}", params=_auth())

            clr = await client.post(
                f"{TRELLO_BASE}/checklists",
                params={**_auth(), "idCard": card_id, "name": "Tasks"},
            )
            clr.raise_for_status()
            cl_id = clr.json()["id"]
            for item in fields["checklist_items"]:
                await client.post(
                    f"{TRELLO_BASE}/checklists/{cl_id}/checkItems",
                    params={**_auth(), "name": item},
                )

    return {"card_id": card_id, "updated_fields": list(fields.keys())}


@mcp.tool()
async def get_urgent_cards(
    board_id: str,
    limit: int = 5,
    list_names: list[str] | None = None,
) -> dict:
    """Returns the most urgent cards from a board sorted by due date then 'urgent' label. Excludes Done/Archive lists by default."""
    exclude = {"Done", "Archive"}

    async with httpx.AsyncClient() as client:
        lsr = await client.get(f"{TRELLO_BASE}/boards/{board_id}/lists", params={**_auth(), "fields": "name,id"})
        lsr.raise_for_status()
        all_lists = lsr.json()

        if list_names:
            target_ids = {l["id"] for l in all_lists if l["name"] in list_names}
        else:
            target_ids = {l["id"] for l in all_lists if l["name"] not in exclude}

        car = await client.get(
            f"{TRELLO_BASE}/boards/{board_id}/cards",
            params={**_auth(), "fields": "name,desc,due,idList,labels,shortUrl", "checklists": "all"},
        )
        car.raise_for_status()
        cards = [c for c in car.json() if c["idList"] in target_ids]

    def _sort(c: dict) -> tuple:
        urgent = any(l["name"].lower() == "urgent" for l in c.get("labels", []))
        due = c.get("due")
        if due:
            return (0, due, not urgent)
        return (1 if urgent else 2, "", False)

    cards.sort(key=_sort)

    return {
        "cards": [
            {
                "card_id": c["id"],
                "title": c["name"],
                "due": c.get("due"),
                "list_id": c["idList"],
                "labels": [l["name"] for l in c.get("labels", [])],
                "url": c.get("shortUrl", ""),
                "checklist_items": [
                    {"name": item["name"], "checked": item["state"] == "complete"}
                    for cl in c.get("checklists", [])
                    for item in cl.get("checkItems", [])
                ],
            }
            for c in cards[:limit]
        ]
    }


@mcp.tool()
async def get_all_cards(
    board_id: str,
    exclude_lists: list[str] | None = None,
) -> dict:
    """Returns every card on a board. Pass exclude_lists to skip lists by name (e.g. ['Done', 'Archive'])."""
    excluded = set(exclude_lists or [])

    async with httpx.AsyncClient() as client:
        if excluded:
            lsr = await client.get(f"{TRELLO_BASE}/boards/{board_id}/lists", params={**_auth(), "fields": "name,id"})
            lsr.raise_for_status()
            skip_ids = {l["id"] for l in lsr.json() if l["name"] in excluded}
        else:
            skip_ids = set()

        car = await client.get(
            f"{TRELLO_BASE}/boards/{board_id}/cards",
            params={**_auth(), "fields": "name,desc,due,idList,labels,shortUrl", "checklists": "all"},
        )
        car.raise_for_status()

    cards = [c for c in car.json() if c["idList"] not in skip_ids]

    return {
        "cards": [
            {
                "card_id": c["id"],
                "title": c["name"],
                "description": c.get("desc", ""),
                "due": c.get("due"),
                "list_id": c["idList"],
                "labels": [l["name"] for l in c.get("labels", [])],
                "url": c.get("shortUrl", ""),
                "checklist_items": [
                    {"name": item["name"], "checked": item["state"] == "complete"}
                    for cl in c.get("checklists", [])
                    for item in cl.get("checkItems", [])
                ],
            }
            for c in cards
        ]
    }


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
