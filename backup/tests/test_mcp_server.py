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
    CardMove,
    CardUpdate,
    NewCard,
    ToolError,
    _build_handles,
    _clean,
    _cron_update_card_statuses,
    _slug,
    create_cards,
    describe_board,
    get_card,
    get_cards,
    move_cards,
    update_cards,
)

# fastmcp 3.x: @mcp.tool() returns the original coroutine (and registers it),
# so tools are called directly — there is no .fn wrapper to unwrap.


# ── Fixtures / helpers ───────────────────────────────────────────────────────

BOARD = {
    "id": "b1",
    "name": "FRM",
    "lists": [
        {"id": "l-auth", "name": "Auth"},
        {"id": "l-done", "name": "Done"},
    ],
}


def async_resp(data, status=200):
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


def card(cid, name, list_id, **extra):
    base = {"id": cid, "name": name, "idList": list_id, "desc": "", "due": None,
            "labels": [], "shortUrl": f"https://trello.com/c/{cid}", "shortLink": cid,
            "checklists": []}
    base.update(extra)
    return base


def gcard(link, name, after=(), est=None, feature=None, done=False):
    """Graph-shaped card for the pure scheduler tests."""
    lines = [f"after: {a}" for a in after]
    if feature:
        lines.append(f"feature: {feature}")
    if est is not None:
        lines.append(f"est: {est}")
    desc = "prose\n\n```meta\n" + "\n".join(lines) + "\n```" if lines else "prose"
    return {"shortLink": link, "name": name, "idList": "L", "desc": desc,
            "labels": [{"name": "done"}] if done else []}


def future_today() -> str:
    return (datetime.now(timezone.utc) + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ")


# ── Pure helpers ─────────────────────────────────────────────────────────────

def test_slug_kebabs_and_strips():
    assert _slug("Google SSO") == "google-sso"
    assert _slug("  Email / Password!! ") == "email-password"
    assert _slug("") == "untitled"


def test_clean_drops_empties_keeps_zero_and_false():
    assert _clean({"a": "", "b": None, "c": [], "d": {}, "e": 0, "f": False, "g": "x"}) == {"e": 0, "f": False, "g": "x"}


def test_build_handles_collision_gets_tiebreak():
    cards = [card("aaaa1111", "Deploy", "l-auth"), card("bbbb2222", "Deploy", "l-auth"),
             card("cccc3333", "Login", "l-auth")]
    names = {"l-auth": "Auth"}
    h = _build_handles(cards, "frm", names)
    assert h["cccc3333"] == "frm/auth/login"               # unique → no suffix
    assert h["aaaa1111"] == "frm/auth/deploy~aaaa"          # collision → deterministic id prefix
    assert h["bbbb2222"] == "frm/auth/deploy~bbbb"


# ── describe_board ───────────────────────────────────────────────────────────

async def test_describe_board_counts_per_list():
    cards = [card("c1", "A", "l-auth"), card("c2", "B", "l-auth"), card("c3", "C", "l-done")]
    ctx, _ = make_async_ctx(async_resp([BOARD]), async_resp(cards))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await describe_board("frm")
    assert result["board"] == "FRM"
    assert result["total_cards"] == 3
    assert result["lists"] == [{"list": "Auth", "cards": 2}, {"list": "Done", "cards": 1}]


async def test_describe_board_unknown_suggests():
    ctx, _ = make_async_ctx(async_resp([BOARD]))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        with pytest.raises(ToolError) as e:
            await describe_board("frn")
    assert "FRM" in str(e.value)


# ── get_cards ────────────────────────────────────────────────────────────────

async def test_get_cards_compact_lines_omit_empty():
    cards = [
        card("c1", "Google SSO", "l-auth"),
        card("c2", "Email Login", "l-auth", due="2026-07-20T17:00:00Z",
             labels=[{"name": "urgent"}],
             checklists=[{"checkItems": [{"name": "x", "state": "complete"},
                                         {"name": "y", "state": "incomplete"}]}]),
    ]
    ctx, _ = make_async_ctx(async_resp([BOARD]), async_resp(cards))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        out = await get_cards("frm")
    lines = out.split("\n")
    assert lines[0] == "frm/auth/google-sso\tGoogle SSO\tAuth"          # no meta on empty card
    assert lines[1] == "frm/auth/email-login\tEmail Login\tAuth\tdue:2026-07-20\t[urgent]\tcheck:1/2"


async def test_get_cards_filters_combine():
    cards = [
        card("c1", "Has due", "l-auth", due="2026-07-10T00:00:00Z"),
        card("c2", "Later due", "l-auth", due="2026-07-25T00:00:00Z"),
        card("c3", "No due", "l-auth"),
        card("c4", "In done", "l-done", due="2026-07-10T00:00:00Z"),
    ]
    ctx, _ = make_async_ctx(async_resp([BOARD]), async_resp(cards))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        out = await get_cards("frm", list_name="Auth", has_due=True, due_before="2026-07-15")
    assert out == "frm/auth/has-due\tHas due\tAuth\tdue:2026-07-10"


async def test_get_cards_no_match_message():
    ctx, _ = make_async_ctx(async_resp([BOARD]), async_resp([]))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        out = await get_cards("frm", text="nothing")
    assert out == "No cards match."


# ── get_card ─────────────────────────────────────────────────────────────────

async def test_get_card_full_detail_omit_empty():
    cards = [card("c1", "Google SSO", "l-auth", desc="Wire OAuth",
                  labels=[{"name": "urgent"}],
                  checklists=[{"checkItems": [{"name": "step", "state": "complete"}]}])]
    ctx, _ = make_async_ctx(async_resp([BOARD]), async_resp(cards))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await get_card("frm/auth/google-sso")
    assert result == {
        "handle": "frm/auth/google-sso",
        "title": "Google SSO",
        "list": "Auth",
        "description": "Wire OAuth",
        "labels": ["urgent"],
        "checklist": [{"name": "step", "done": True}],
        "url": "https://trello.com/c/c1",
    }
    assert "due" not in result  # omit-empty


async def test_get_card_unresolvable_handle_suggests():
    cards = [card("c1", "Google SSO", "l-auth")]
    ctx, _ = make_async_ctx(async_resp([BOARD]), async_resp(cards))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        with pytest.raises(ToolError) as e:
            await get_card("frm/auth/google")
    assert "google-sso" in str(e.value)


# ── create_cards (batch) ─────────────────────────────────────────────────────

async def test_create_cards_batch_names_resolved():
    created1 = {"id": "n1", "shortUrl": "https://trello.com/c/n1", "shortLink": "n1"}
    created2 = {"id": "n2", "shortUrl": "https://trello.com/c/n2", "shortLink": "n2"}
    ctx, client = make_async_ctx(
        async_resp([BOARD]),                              # boards
        async_resp([{"name": "urgent", "id": "lbl-u"}]),  # labels for card 2
        post_seq=[async_resp(created1), async_resp(created2)],
        put_data=async_resp({}),
    )
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await create_cards(board="frm", list_name="Auth", cards=[
            NewCard(title="First"),
            NewCard(title="Second", labels=["urgent"]),
        ])
    assert result["created"] == [
        {"handle": "frm/auth/first", "url": "https://trello.com/c/n1"},
        {"handle": "frm/auth/second", "url": "https://trello.com/c/n2"},
    ]
    assert "errors" not in result
    second_params = client.post.call_args_list[1][1]["params"]
    assert second_params["idList"] == "l-auth"
    assert second_params["idLabels"] == "lbl-u"
    # feature defaults to the list name and is written in the meta pass
    assert "feature: auth" in client.put.call_args_list[0][1]["params"]["desc"]


async def test_create_cards_per_item_error_isolated():
    created = {"id": "n1", "shortUrl": "", "shortLink": "n1"}
    ctx, _ = make_async_ctx(async_resp([BOARD]), post_seq=[async_resp(created), Exception("boom")],
                            put_data=async_resp({}))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await create_cards(board="frm", list_name="Auth", cards=[
            NewCard(title="Good"),
            NewCard(title="Bad"),
        ])
    assert result["created"] == [{"handle": "frm/auth/good", "url": ""}]
    assert result["errors"][0]["index"] == 1


async def test_create_cards_flags_too_big():
    created = {"id": "n1", "shortUrl": "", "shortLink": "n1"}
    ctx, _ = make_async_ctx(async_resp([BOARD]), post_seq=[async_resp(created)], put_data=async_resp({}))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await create_cards(board="frm", list_name="Auth", cards=[NewCard(title="Huge", est=4)])
    assert result["too_big"] == [{"card": "Huge", "est": 4}]
    assert "split" in result["advice"].lower()


# ── meta block ───────────────────────────────────────────────────────────────

def test_meta_round_trips_and_is_idempotent():
    d = mcp_server._set_meta("prose", [("kPq3xR1a", "Session")], 2.0, "auth")
    assert mcp_server._parse_meta(d) == (["kPq3xR1a"], 2.0, "auth")
    assert mcp_server._set_meta(d, [("kPq3xR1a", "Session")], 2.0, "auth") == d
    assert d.startswith("prose")


def test_meta_absent_or_junk_is_harmless():
    assert mcp_server._parse_meta("") == ([], None, None)
    assert mcp_server._parse_meta("just prose") == ([], None, None)
    assert mcp_server._parse_meta("```meta\nest: notanumber\n```") == ([], None, None)


def test_is_done_reads_label():
    assert mcp_server._is_done({"labels": [{"name": "done"}]})
    assert not mcp_server._is_done({"labels": [{"name": "urgent"}]})


# ── scheduler (pure) ─────────────────────────────────────────────────────────

def _chain(n):
    return [gcard("n1", "s1")] + [gcard(f"n{i}", f"s{i}", (f"n{i-1}",)) for i in range(2, n + 1)]


def test_schedule_respects_dependency_order():
    p = mcp_server._schedule(_chain(5), start="2026-07-17", pace=2)
    day = {r["card"]: r["date"] for r in p["rows"]}
    assert day["s1"] < day["s2"] < day["s3"] < day["s4"] < day["s5"]


def test_schedule_spreads_and_leaves_no_empty_days():
    cards = [gcard("base", "base")] + [
        gcard(f"x{i}", f"x{i}", ("base",)) for i in range(1, 10)
    ]
    p = mcp_server._schedule(cards, deadline="2026-07-24", start="2026-07-17")
    assert p["window_days"] == 8
    assert all(v > 0 for v in p["load"].values()), p["load"]
    assert sum(p["load"].values()) == 10


def test_schedule_deadline_reports_intensity():
    p = mcp_server._schedule(_chain(4) + [gcard("z", "z")], deadline="2026-07-21", start="2026-07-17")
    assert p["window_days"] == 5
    assert p["intensity"] == 1.0


def test_schedule_without_deadline_uses_pace_and_reports_end():
    cards = [gcard(f"n{i}", f"s{i}") for i in range(8)]
    p = mcp_server._schedule(cards, start="2026-07-17", pace=2)
    assert p["window_days"] == 4
    assert p["end"] == "2026-07-20"


def test_schedule_chain_longer_than_window_stacks_and_says_so():
    p = mcp_server._schedule(_chain(6), deadline="2026-07-19", start="2026-07-17")
    assert p["stacked_chain"] and p["chain"] == 6 and p["window_days"] == 3
    assert max(p["load"].values()) > 1


def test_schedule_detects_cycle_with_the_loop():
    p = mcp_server._schedule([gcard("a", "A", ("c",)), gcard("b", "B", ("a",)), gcard("c", "C", ("b",))])
    assert [p["by_link"][l]["name"] for l in p["cycle"]] == ["A", "C", "B", "A"]


def test_schedule_reports_dangling_edge():
    p = mcp_server._schedule([gcard("a", "A", ("nope",))], start="2026-07-17")
    assert p["dangling"] == [{"card": "A", "unknown_ref": "nope"}]


def test_schedule_deadline_before_start_errors():
    p = mcp_server._schedule([gcard("a", "A")], deadline="2026-07-10", start="2026-07-17")
    assert "error" in p


# ── update_cards (batch) ─────────────────────────────────────────────────────

async def test_update_cards_partial_by_handle():
    cards = [card("c1", "Google SSO", "l-auth")]
    ctx, client = make_async_ctx(async_resp([BOARD]), async_resp(cards), put_data=async_resp({}))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await update_cards([CardUpdate(handle="frm/auth/google-sso", title="Google Sign-In")])
    assert result["updated"] == [{"handle": "frm/auth/google-sso", "applied": ["title"]}]
    assert client.put.call_args[1]["params"]["name"] == "Google Sign-In"


async def test_update_cards_clear_due():
    cards = [card("c1", "Google SSO", "l-auth")]
    ctx, client = make_async_ctx(async_resp([BOARD]), async_resp(cards), put_data=async_resp({}))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        await update_cards([CardUpdate(handle="frm/auth/google-sso", due="null")])
    assert client.put.call_args[1]["params"]["due"] == "null"


async def test_update_cards_replaces_checklist():
    cards = [card("c1", "Google SSO", "l-auth")]
    ctx, client = make_async_ctx(
        async_resp([BOARD]),
        async_resp(cards),
        async_resp([{"id": "old-cl"}]),   # existing checklists on the card
        post_seq=[async_resp({"id": "new-cl"}), async_resp({})],
        delete_data=async_resp({}),
    )
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await update_cards([CardUpdate(handle="frm/auth/google-sso", checklist=["Fresh"])])
    client.delete.assert_called_once()
    assert result["updated"][0]["applied"] == ["checklist"]


# ── move_cards (batch) ───────────────────────────────────────────────────────

async def test_move_cards_returns_new_handle():
    cards = [card("c1", "Google SSO", "l-auth")]
    ctx, client = make_async_ctx(async_resp([BOARD]), async_resp(cards), put_data=async_resp({}))
    with patch("mcp_server.httpx.AsyncClient", return_value=ctx):
        result = await move_cards([CardMove(handle="frm/auth/google-sso", to_list="Done")])
    assert result["moved"] == [{"from": "frm/auth/google-sso", "to": "frm/done/google-sso"}]
    assert client.put.call_args[1]["params"]["idList"] == "l-done"


# ── Cron (unchanged behaviour) ───────────────────────────────────────────────

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
    boards = [{"id": "b-resolved", "name": "My Board"}]
    due_today = future_today()
    cards = [{"id": "c1", "due": due_today, "idLabels": []}]
    labels = [{"name": "due-today", "id": "lbl-today"}]
    original_id = mcp_server.TRELLO_CRON_BOARD_ID
    original_name = mcp_server.TRELLO_CRON_BOARD_NAME
    mcp_server.TRELLO_CRON_BOARD_ID = ""
    mcp_server.TRELLO_CRON_BOARD_NAME = "My Board"
    ctx, client = make_sync_ctx(
        sync_resp(boards), sync_resp(cards), sync_resp(labels), put_data=sync_resp({}),
    )
    with patch("mcp_server.httpx.Client", return_value=ctx):
        _cron_update_card_statuses()
    client.put.assert_called_once()
    mcp_server.TRELLO_CRON_BOARD_ID = original_id
    mcp_server.TRELLO_CRON_BOARD_NAME = original_name
