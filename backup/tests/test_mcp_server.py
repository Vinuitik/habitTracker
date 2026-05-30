import os
import sys
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Ensure backup/ is importable and env vars are set before module load
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
os.environ.setdefault("TRELLO_API_KEY", "test-key")
os.environ.setdefault("TRELLO_TOKEN", "test-token")
os.environ.setdefault("TRELLO_CRON_BOARD_ID", "board-001")

import mcp_server
from mcp_server import (
    _cron_update_card_statuses,
    create_card,
    get_urgent_cards,
    list_boards_and_lists,
    update_card,
)

# FastMCP 2 wraps @mcp.tool() functions into FunctionTool objects.
# Access the original coroutine via .fn for direct testing.
_list_boards = list_boards_and_lists.fn
_create_card = create_card.fn
_update_card = update_card.fn
_get_urgent = get_urgent_cards.fn


# ── Helpers ─────────────────────────────────────────────────────────────────


def async_resp(data, status=200):
    # httpx responses are sync objects — only the client methods (get/post/…) are async
    m = MagicMock()
    m.json.return_value = data
    m.status_code = status
    m.raise_for_status = MagicMock()
    return m


def sync_resp(data, status=200):
    m = MagicMock()
    m.json.return_value = data
    m.status_code = status
    return m


def make_async_ctx(*get_seq, post_seq=None, put_data=None, delete_data=None):
    client = AsyncMock()
    client.get.side_effect = list(get_seq) if get_seq else None
    if post_seq is not None:
        client.post.side_effect = list(post_seq)
    if put_data is not None:
        client.put.return_value = put_data
    if delete_data is not None:
        client.delete.return_value = delete_data
    ctx = MagicMock()
    ctx.__aenter__ = AsyncMock(return_value=client)
    ctx.__aexit__ = AsyncMock(return_value=False)
    return ctx, client


def make_sync_ctx(*get_seq, put_data=None, delete_data=None):
    client = MagicMock()
    client.get.side_effect = list(get_seq) if get_seq else None
    if put_data is not None:
        client.put.return_value = put_data
    if delete_data is not None:
        client.delete.return_value = delete_data
    ctx = MagicMock()
    ctx.__enter__ = MagicMock(return_value=client)
    ctx.__exit__ = MagicMock(return_value=False)
    return ctx, client


def future_today() -> str:
    """ISO timestamp 2h from now — clearly in the future but still the same calendar day (safe up to 22:00)."""
    return (datetime.now(timezone.utc) + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ")


# ── list_boards_and_lists ────────────────────────────────────────────────────


async def test_list_boards_returns_structure():
    boards = [
        {"id": "b1", "name": "Dev", "lists": [{"id": "l1", "name": "To Do"}, {"id": "l2", "name": "Done"}]},
        {"id": "b2", "name": "Personal", "lists": []},
    ]
    ctx, _ = make_async_ctx(async_resp(boards))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _list_boards()

    assert len(result["boards"]) == 2
    assert result["boards"][0] == {
        "board_id": "b1",
        "board_name": "Dev",
        "lists": [{"list_id": "l1", "list_name": "To Do"}, {"list_id": "l2", "list_name": "Done"}],
    }
    assert result["boards"][1]["lists"] == []


async def test_list_boards_empty():
    ctx, _ = make_async_ctx(async_resp([]))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _list_boards()

    assert result == {"boards": []}


# ── create_card ──────────────────────────────────────────────────────────────


async def test_create_card_minimal():
    list_info = {"idBoard": "b1"}
    card = {"id": "card-1", "shortUrl": "https://trello.com/c/abc"}
    ctx, client = make_async_ctx(async_resp(list_info), post_seq=[async_resp(card)])

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _create_card(list_id="l1", title="My Card", description="Details")

    assert result == {"card_id": "card-1", "card_url": "https://trello.com/c/abc", "title": "My Card"}
    client.post.assert_called_once()  # card only, no checklist


async def test_create_card_with_checklist_and_labels():
    list_info = {"idBoard": "b1"}
    board_labels = [{"name": "feature", "id": "lbl-feat"}, {"name": "urgent", "id": "lbl-urg"}]
    card = {"id": "card-2", "shortUrl": "https://trello.com/c/xyz"}
    checklist = {"id": "cl-1"}

    ctx, client = make_async_ctx(
        async_resp(list_info),
        async_resp(board_labels),
        post_seq=[async_resp(card), async_resp(checklist), async_resp({}), async_resp({})],
    )

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _create_card(
            list_id="l1",
            title="Feature",
            description="Build X",
            checklist_items=["Step 1", "Step 2"],
            labels=["feature", "urgent"],
        )

    assert result["card_id"] == "card-2"
    assert client.post.call_count == 4  # card + checklist + 2 items


async def test_create_card_unknown_labels_silently_skipped():
    list_info = {"idBoard": "b1"}
    board_labels = [{"name": "bug", "id": "lbl-bug"}]
    card = {"id": "card-3", "shortUrl": ""}

    ctx, client = make_async_ctx(
        async_resp(list_info),
        async_resp(board_labels),
        post_seq=[async_resp(card)],
    )

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _create_card(list_id="l1", title="T", description="D", labels=["nonexistent"])

    assert result["card_id"] == "card-3"
    assert "idLabels" not in str(client.post.call_args)


async def test_create_card_with_due_date():
    list_info = {"idBoard": "b1"}
    card = {"id": "card-4", "shortUrl": ""}
    ctx, client = make_async_ctx(async_resp(list_info), post_seq=[async_resp(card)])

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        await _create_card(list_id="l1", title="T", description="D", due_date="2025-06-15T17:00:00Z")

    post_params = client.post.call_args[1]["params"]
    assert post_params["due"] == "2025-06-15T17:00:00Z"


# ── update_card ──────────────────────────────────────────────────────────────


async def test_update_card_title_only():
    ctx, client = make_async_ctx(put_data=async_resp({}))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _update_card("card-1", {"title": "New Title"})

    assert result == {"card_id": "card-1", "updated_fields": ["title"]}
    client.put.assert_called_once()
    assert client.put.call_args[1]["params"]["name"] == "New Title"


async def test_update_card_clears_due_date():
    ctx, client = make_async_ctx(put_data=async_resp({}))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        await _update_card("card-1", {"due_date": None})

    assert client.put.call_args[1]["params"]["due"] == "null"


async def test_update_card_moves_list():
    ctx, client = make_async_ctx(put_data=async_resp({}))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _update_card("card-1", {"list_id": "l-done"})

    assert client.put.call_args[1]["params"]["idList"] == "l-done"
    assert "list_id" in result["updated_fields"]


async def test_update_card_replaces_labels():
    card_info = {"idBoard": "b1"}
    board_labels = [{"name": "feature", "id": "lbl-feat"}, {"name": "bug", "id": "lbl-bug"}]

    ctx, client = make_async_ctx(
        async_resp(card_info),
        async_resp(board_labels),
        put_data=async_resp({}),
    )

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        await _update_card("card-1", {"labels": ["bug"]})

    label_put_params = client.put.call_args[1]["params"]
    assert "lbl-bug" in label_put_params["idLabels"]
    assert "lbl-feat" not in label_put_params["idLabels"]


async def test_update_card_replaces_checklist():
    existing_cls = [{"id": "old-cl"}]
    new_cl = {"id": "new-cl"}

    ctx, client = make_async_ctx(
        async_resp(existing_cls),
        post_seq=[async_resp(new_cl), async_resp({})],
        delete_data=async_resp({}),
        put_data=async_resp({}),
    )

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _update_card("card-1", {"checklist_items": ["New step"]})

    client.delete.assert_called_once()  # old checklist deleted
    assert client.post.call_count == 2  # new checklist + 1 item
    assert result["updated_fields"] == ["checklist_items"]


async def test_update_card_no_fields_no_put():
    ctx, client = make_async_ctx(put_data=async_resp({}))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        await _update_card("card-1", {})

    client.put.assert_not_called()


# ── get_urgent_cards ─────────────────────────────────────────────────────────


async def test_get_urgent_cards_sort_order():
    """Due-date cards first, urgent-no-due second, rest last; Done excluded."""
    lists = [
        {"id": "l-todo", "name": "To Do"},
        {"id": "l-done", "name": "Done"},
    ]
    cards = [
        {"id": "c-nodue", "name": "No due", "due": None, "idList": "l-todo", "labels": [], "shortUrl": "", "checklists": []},
        {"id": "c-urgent", "name": "Urgent no due", "due": None, "idList": "l-todo", "labels": [{"name": "urgent"}], "shortUrl": "", "checklists": []},
        {"id": "c-due", "name": "Has due", "due": "2025-06-01T10:00:00Z", "idList": "l-todo", "labels": [], "shortUrl": "", "checklists": []},
        {"id": "c-done", "name": "In Done", "due": None, "idList": "l-done", "labels": [], "shortUrl": "", "checklists": []},
    ]

    ctx, _ = make_async_ctx(async_resp(lists), async_resp(cards))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _get_urgent(board_id="b1", limit=10)

    ids = [c["card_id"] for c in result["cards"]]
    assert ids == ["c-due", "c-urgent", "c-nodue"]
    assert "c-done" not in ids


async def test_get_urgent_cards_respects_limit():
    lists = [{"id": "l1", "name": "To Do"}]
    cards = [
        {"id": f"c{i}", "name": f"Card {i}", "due": f"2025-06-0{i}T10:00:00Z",
         "idList": "l1", "labels": [], "shortUrl": "", "checklists": []}
        for i in range(1, 6)
    ]

    ctx, _ = make_async_ctx(async_resp(lists), async_resp(cards))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _get_urgent(board_id="b1", limit=3)

    assert len(result["cards"]) == 3


async def test_get_urgent_cards_list_names_filter():
    lists = [
        {"id": "l-todo", "name": "To Do"},
        {"id": "l-wip", "name": "In Progress"},
        {"id": "l-done", "name": "Done"},
    ]
    cards = [
        {"id": "c1", "due": None, "idList": "l-todo", "name": "In Todo", "labels": [], "shortUrl": "", "checklists": []},
        {"id": "c2", "due": None, "idList": "l-wip", "name": "In WIP", "labels": [], "shortUrl": "", "checklists": []},
        {"id": "c3", "due": None, "idList": "l-done", "name": "In Done", "labels": [], "shortUrl": "", "checklists": []},
    ]

    ctx, _ = make_async_ctx(async_resp(lists), async_resp(cards))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _get_urgent(board_id="b1", list_names=["To Do"])

    assert len(result["cards"]) == 1
    assert result["cards"][0]["card_id"] == "c1"


async def test_get_urgent_cards_includes_checklist_state():
    lists = [{"id": "l1", "name": "To Do"}]
    cards = [{
        "id": "c1", "name": "Card", "due": None, "idList": "l1", "labels": [], "shortUrl": "",
        "checklists": [{"checkItems": [
            {"name": "Step 1", "state": "complete"},
            {"name": "Step 2", "state": "incomplete"},
        ]}],
    }]

    ctx, _ = make_async_ctx(async_resp(lists), async_resp(cards))

    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await _get_urgent(board_id="b1")

    items = result["cards"][0]["checklist_items"]
    assert items == [{"name": "Step 1", "checked": True}, {"name": "Step 2", "checked": False}]


# ── Cron ─────────────────────────────────────────────────────────────────────


def test_cron_labels_overdue_card():
    overdue = (datetime.now(timezone.utc) - timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    cards = [{"id": "c1", "due": overdue, "idLabels": []}]
    labels = [{"name": "overdue", "id": "lbl-overdue"}, {"name": "due-today", "id": "lbl-today"}]

    ctx, client = make_sync_ctx(sync_resp(cards), sync_resp(labels), put_data=sync_resp({}))

    with patch("mcp_server.httpx.Client", return_value=ctx):
        _cron_update_card_statuses()

    put_params = client.put.call_args[1]["params"]
    assert "lbl-overdue" in put_params["idLabels"]
    assert "lbl-today" not in put_params["idLabels"]


def test_cron_labels_due_today_card():
    # Use 2h from now — clearly in the future so it's NOT counted as overdue,
    # but still on today's calendar date (safe up to ~22:00 UTC).
    due_today = future_today()
    cards = [{"id": "c1", "due": due_today, "idLabels": []}]
    labels = [{"name": "overdue", "id": "lbl-overdue"}, {"name": "due-today", "id": "lbl-today"}]

    ctx, client = make_sync_ctx(sync_resp(cards), sync_resp(labels), put_data=sync_resp({}))

    with patch("mcp_server.httpx.Client", return_value=ctx):
        _cron_update_card_statuses()

    put_params = client.put.call_args[1]["params"]
    assert "lbl-today" in put_params["idLabels"]
    assert "lbl-overdue" not in put_params["idLabels"]


def test_cron_no_api_call_when_labels_unchanged():
    """Card already has the correct overdue label — no PUT."""
    overdue = (datetime.now(timezone.utc) - timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    cards = [{"id": "c1", "due": overdue, "idLabels": ["lbl-overdue"]}]
    labels = [{"name": "overdue", "id": "lbl-overdue"}]

    ctx, client = make_sync_ctx(sync_resp(cards), sync_resp(labels))

    with patch("mcp_server.httpx.Client", return_value=ctx):
        _cron_update_card_statuses()

    client.put.assert_not_called()


def test_cron_skips_card_with_no_due_date():
    cards = [{"id": "c1", "due": None, "idLabels": []}]
    labels = [{"name": "overdue", "id": "lbl-overdue"}]

    ctx, client = make_sync_ctx(sync_resp(cards), sync_resp(labels))

    with patch("mcp_server.httpx.Client", return_value=ctx):
        _cron_update_card_statuses()

    client.put.assert_not_called()


def test_cron_no_put_when_board_has_no_matching_labels():
    """Board has no 'overdue' label defined — nothing to add, no PUT."""
    overdue = (datetime.now(timezone.utc) - timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    cards = [{"id": "c1", "due": overdue, "idLabels": []}]
    labels = []

    ctx, client = make_sync_ctx(sync_resp(cards), sync_resp(labels))

    with patch("mcp_server.httpx.Client", return_value=ctx):
        _cron_update_card_statuses()

    client.put.assert_not_called()


def test_cron_skips_when_no_board_configured():
    original_id = mcp_server.TRELLO_CRON_BOARD_ID
    original_name = mcp_server.TRELLO_CRON_BOARD_NAME
    mcp_server.TRELLO_CRON_BOARD_ID = ""
    mcp_server.TRELLO_CRON_BOARD_NAME = ""

    with patch("mcp_server.httpx.Client") as MockClient:
        _cron_update_card_statuses()
        MockClient.assert_not_called()

    mcp_server.TRELLO_CRON_BOARD_ID = original_id
    mcp_server.TRELLO_CRON_BOARD_NAME = original_name


def test_cron_resolves_board_name_to_id():
    """When only TRELLO_CRON_BOARD_NAME is set, cron resolves board ID before running."""
    boards = [{"id": "b-resolved", "name": "My Board"}]
    due_today = future_today()
    cards = [{"id": "c1", "due": due_today, "idLabels": []}]
    labels = [{"name": "due-today", "id": "lbl-today"}]

    original_id = mcp_server.TRELLO_CRON_BOARD_ID
    original_name = mcp_server.TRELLO_CRON_BOARD_NAME
    mcp_server.TRELLO_CRON_BOARD_ID = ""
    mcp_server.TRELLO_CRON_BOARD_NAME = "My Board"

    # All three GETs share the same mock client across the two httpx.Client() context blocks
    ctx, client = make_sync_ctx(
        sync_resp(boards),   # 1st GET: board name resolution
        sync_resp(cards),    # 2nd GET: cards fetch
        sync_resp(labels),   # 3rd GET: labels fetch
        put_data=sync_resp({}),
    )

    with patch("mcp_server.httpx.Client", return_value=ctx):
        _cron_update_card_statuses()

    client.put.assert_called_once()

    mcp_server.TRELLO_CRON_BOARD_ID = original_id
    mcp_server.TRELLO_CRON_BOARD_NAME = original_name
