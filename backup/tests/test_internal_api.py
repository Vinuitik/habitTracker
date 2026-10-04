import json
import os
import sys
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
os.environ.setdefault("TRELLO_API_KEY", "test-key")
os.environ.setdefault("TRELLO_TOKEN", "test-token")

import claude_cli
import internal_api

H = {}


@pytest.fixture
def client():
    return TestClient(internal_api.app)


def resp(data, status=200):
    m = MagicMock()
    m.json.return_value = data
    m.status_code = status
    m.raise_for_status = MagicMock()
    return m


def http_ctx(client_mock):
    ctx = MagicMock()
    ctx.__aenter__ = AsyncMock(return_value=client_mock)
    ctx.__aexit__ = AsyncMock(return_value=False)
    return ctx


# ── boards ───────────────────────────────────────────────────────────────────

def test_create_board(client):
    c = AsyncMock()
    c.post.return_value = resp({"id": "b123"})
    with patch("internal_api.httpx.AsyncClient", return_value=http_ctx(c)):
        r = client.post("/internal/boards", json={"name": "Proj"}, headers=H)
    assert r.json() == {"boardId": "b123"}
    assert c.post.call_args.kwargs["params"]["name"] == "Proj"


def test_delete_board_hard_deletes(client):
    c = AsyncMock()
    c.delete.return_value = resp({}, 200)
    with patch("internal_api.httpx.AsyncClient", return_value=http_ctx(c)):
        r = client.delete("/internal/boards/b123", headers=H)
    assert r.status_code == 200
    assert c.delete.call_args.args[0].endswith("/boards/b123")


def test_delete_board_404(client):
    c = AsyncMock()
    c.delete.return_value = resp({}, 404)
    with patch("internal_api.httpx.AsyncClient", return_value=http_ctx(c)):
        assert client.delete("/internal/boards/zz", headers=H).status_code == 404


# ── plan ─────────────────────────────────────────────────────────────────────

PLAN = {"cards": [{"handle": "p/a/b"}], "proposal": {"rows": []}}


def test_plan_returns_cards_and_proposal(client):
    with patch("internal_api._board_name", AsyncMock(return_value="Proj")), \
         patch("internal_api.claude_cli.run_plan", AsyncMock(return_value=PLAN)) as run:
        r = client.post("/internal/agent/plan", json={"boardId": "b1", "description": "do x"}, headers=H)
    assert r.json() == PLAN
    run.assert_awaited_once_with("Proj", "do x")


def test_plan_concurrent_is_409(client):
    internal_api._plan_lock._locked = True  # simulate in-flight request
    try:
        r = client.post("/internal/agent/plan", json={"boardId": "b1", "description": "d"}, headers=H)
    finally:
        internal_api._plan_lock._locked = False
    assert r.status_code == 409


def test_plan_cli_error_maps_status(client):
    with patch("internal_api._board_name", AsyncMock(return_value="Proj")), \
         patch("internal_api.claude_cli.run_plan",
               AsyncMock(side_effect=claude_cli.CliError("timeout", status=504))):
        r = client.post("/internal/agent/plan", json={"boardId": "b1", "description": "d"}, headers=H)
    assert r.status_code == 504
    assert not internal_api._plan_lock.locked()  # released after failure


# ── apply ────────────────────────────────────────────────────────────────────

def test_apply_calls_apply_schedule(client):
    with patch("internal_api._board_name", AsyncMock(return_value="Proj")), \
         patch("internal_api.apply_schedule", AsyncMock(return_value={"moved": 3})) as ap:
        r = client.post("/internal/agent/apply",
                        json={"boardId": "b1", "deadline": "2026-12-01", "pace": 3}, headers=H)
    assert r.json() == {"moved": 3}
    ap.assert_awaited_once_with(board="Proj", deadline="2026-12-01", pace=3)


# ── claude_cli ───────────────────────────────────────────────────────────────

def test_cmd_restricts_tools_and_excludes_apply():
    cmd = claude_cli.build_cmd("p")
    assert cmd[:2] == ["claude", "-p"]
    assert "json" in cmd and "--strict-mcp-config" in cmd
    allowed = cmd[cmd.index("--allowedTools") + 1]
    assert "mcp__trello__propose_schedule" in allowed
    assert "apply_schedule" not in allowed
    assert "localhost:8091/mcp" in cmd[cmd.index("--mcp-config") + 1]


def test_parse_result_strips_fences():
    env = {"result": "```json\n" + json.dumps(PLAN) + "\n```"}
    assert claude_cli.parse_result(json.dumps(env)) == PLAN


@pytest.mark.parametrize("out", ["not json", json.dumps({"is_error": True, "result": "x"}),
                                 json.dumps({"result": "no object"}),
                                 json.dumps({"result": '{"cards": []}'})])
def test_parse_result_errors(out):
    with pytest.raises(claude_cli.CliError):
        claude_cli.parse_result(out)


def fake_proc(stdout=b"", stderr=b"", code=0):
    p = MagicMock()
    p.communicate = AsyncMock(return_value=(stdout, stderr))
    p.returncode = code
    p.kill = MagicMock()
    p.wait = AsyncMock()
    return p


async def test_run_plan_success():
    out = json.dumps({"result": json.dumps(PLAN)}).encode()
    with patch("claude_cli.asyncio.create_subprocess_exec", AsyncMock(return_value=fake_proc(out))):
        assert await claude_cli.run_plan("Proj", "d") == PLAN


async def test_run_plan_nonzero_exit():
    with patch("claude_cli.asyncio.create_subprocess_exec",
               AsyncMock(return_value=fake_proc(b"", b"auth expired", 1))):
        with pytest.raises(claude_cli.CliError, match="auth expired"):
            await claude_cli.run_plan("Proj", "d")


async def test_run_plan_timeout_kills():
    p = fake_proc()
    p.communicate = AsyncMock(side_effect=__import__("asyncio").TimeoutError)
    with patch("claude_cli.asyncio.create_subprocess_exec", AsyncMock(return_value=p)):
        with pytest.raises(claude_cli.CliError) as e:
            await claude_cli.run_plan("Proj", "d")
    assert e.value.status == 504
    p.kill.assert_called_once()
