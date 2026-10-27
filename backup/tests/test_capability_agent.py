"""M8: /internal/agent/capability endpoint + claude_cli capability functions (subprocess mocked)."""
import asyncio
import json
import os
import sys
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
os.environ.setdefault("TRELLO_API_KEY", "test-key")
os.environ.setdefault("TRELLO_TOKEN", "test-token")

import claude_cli
import internal_api

PROPOSAL = {"reply": "One way.", "options": [{"title": "Lines", "summary": "Count lines", "platform": "SERVER",
            "declaredCapabilities": {"network": [], "secrets": []}}]}
BODY = {"mode": "propose", "kpiName": "K", "description": "d"}


@pytest.fixture
def client():
    return TestClient(internal_api.app)


def envelope(obj):
    return json.dumps({"result": json.dumps(obj)})


def fake_proc(stdout=b"", stderr=b"", code=0):
    p = MagicMock()
    p.communicate = AsyncMock(return_value=(stdout, stderr))
    p.returncode = code
    p.kill = MagicMock()
    p.wait = AsyncMock()
    return p


# ── endpoint ─────────────────────────────────────────────────────────────────

def test_propose_returns_result(client):
    with patch("internal_api.claude_cli.run_capability", AsyncMock(return_value=PROPOSAL)) as run:
        r = client.post("/internal/agent/capability", json=BODY)
    assert r.json() == PROPOSAL
    run.assert_awaited_once_with("propose", "K", "d", None, None, None, None,
                                 conversation=None, previous_options=None)


def test_refine_passes_conversation(client):
    convo = [{"role": "user", "text": "only free"}]
    prev = [{"title": "Paid", "summary": "x"}]
    with patch("internal_api.claude_cli.run_capability", AsyncMock(return_value=PROPOSAL)) as run:
        client.post("/internal/agent/capability", json={**BODY, "conversation": convo, "previousOptions": prev})
    assert run.await_args.kwargs == {"conversation": convo, "previous_options": prev}


def test_generate_requires_proposal(client):
    r = client.post("/internal/agent/capability", json={**BODY, "mode": "generate"})
    assert r.status_code == 422


def test_bad_mode_is_422(client):
    assert client.post("/internal/agent/capability", json={**BODY, "mode": "apply"}).status_code == 422


def test_concurrent_is_409(client):
    internal_api._capability_lock._locked = True  # simulate in-flight generation
    try:
        r = client.post("/internal/agent/capability", json=BODY)
    finally:
        internal_api._capability_lock._locked = False
    assert r.status_code == 409


def test_cli_error_maps_status_and_releases_lock(client):
    with patch("internal_api.claude_cli.run_capability",
               AsyncMock(side_effect=claude_cli.CliError("timeout", status=504))):
        r = client.post("/internal/agent/capability", json=BODY)
    assert r.status_code == 504
    assert not internal_api._capability_lock.locked()


# ── claude_cli ───────────────────────────────────────────────────────────────

def test_propose_cmd_allows_only_web_tools_and_no_mcp_servers():
    cmd = claude_cli.build_capability_cmd("p", "propose")
    assert cmd[cmd.index("--tools") + 1] == "WebSearch,WebFetch"
    assert cmd[cmd.index("--allowedTools") + 1] == "WebSearch,WebFetch"
    assert json.loads(cmd[cmd.index("--mcp-config") + 1]) == {"mcpServers": {}}
    assert "--strict-mcp-config" in cmd


def test_cmd_has_no_tools_and_no_mcp_servers():
    cmd = claude_cli.build_capability_cmd("p")
    assert "--allowedTools" not in cmd
    assert cmd[cmd.index("--tools") + 1] == ""
    assert json.loads(cmd[cmd.index("--mcp-config") + 1]) == {"mcpServers": {}}
    assert "--strict-mcp-config" in cmd


def test_prompt_feeds_error_back():
    p = claude_cli.build_capability_prompt("generate", "K", "d", "spec", {"network": []},
                                           "print(1/0)", "ZeroDivisionError")
    assert "print(1/0)" in p and "ZeroDivisionError" in p and "spec" in p
    assert "FAILED" not in claude_cli.build_capability_prompt("generate", "K", "d", "spec")


def test_parse_propose_normalises_options():
    out = claude_cli.parse_capability_result("propose", envelope({"reply": " hi ", "options": [
        {"title": "A", "summary": " x ", "platform": "server", "declaredCapabilities": {"network": ["a.com"]},
         "cost": "free_tier", "method": "telepathy", "sources": ["https://a.com/p", "javascript:alert(1)", 3]},
        {"title": "no summary"},
    ]}))
    assert out["reply"] == "hi"
    assert len(out["options"]) == 1
    o = out["options"][0]
    assert o["summary"] == "x" and o["platform"] == "SERVER"
    assert o["declaredCapabilities"] == {"network": ["a.com"], "secrets": []}
    assert (o["cost"], o["method"], o["difficulty"]) == ("FREE_TIER", "UNKNOWN", "UNKNOWN")
    assert o["sources"] == ["https://a.com/p"]


def test_parse_propose_accepts_single_proposal_shape():
    out = claude_cli.parse_capability_result("propose", envelope({"proposal": "x", "platform": "SERVER"}))
    assert out["options"][0]["summary"] == "x" and out["reply"] is None


def test_refine_prompt_includes_conversation_and_previous_options():
    p = claude_cli.build_capability_prompt("propose", "K", "d", conversation=[{"role": "user", "text": "no scraping"}],
                                           previous_options=[{"title": "Scrape it"}])
    assert "user: no scraping" in p and "Scrape it" in p
    assert "Conversation so far" not in claude_cli.build_capability_prompt("propose", "K", "d")


@pytest.mark.parametrize("mode,obj", [
    ("propose", {"platform": "SERVER"}),
    ("propose", {"options": []}),
    ("propose", {"options": [{"title": "no summary"}]}),
    ("propose", {"options": [{"summary": "x", "declaredCapabilities": "nope"}]}),
    ("propose", {"options": [{"summary": "x", "declaredCapabilities": {"network": "a.com"}}]}),
    ("generate", {"sourceCode": "  "}),
    ("generate", {"other": 1}),
    ("generate", {"sourceCode": "x" * (64 * 1024 + 1)}),
])
def test_parse_errors(mode, obj):
    with pytest.raises(claude_cli.CliError):
        claude_cli.parse_capability_result(mode, envelope(obj))


def test_parse_not_json():
    with pytest.raises(claude_cli.CliError):
        claude_cli.parse_capability_result("generate", "garbage")


def test_parse_generate_strips_fences():
    out = claude_cli.parse_capability_result("generate", envelope({"sourceCode": "```python\nprint(1)\n```"}))
    assert out == {"sourceCode": "print(1)"}


async def test_run_capability_success_without_mcp():
    p = fake_proc(envelope({"sourceCode": "print(2)"}).encode())
    with patch("claude_cli.asyncio.create_subprocess_exec", AsyncMock(return_value=p)) as ex:
        out = await claude_cli.run_capability("generate", "K", "d", "spec", {})
    assert out == {"sourceCode": "print(2)"}
    assert "--allowedTools" not in ex.call_args.args


async def test_run_capability_nonzero_exit():
    with patch("claude_cli.asyncio.create_subprocess_exec",
               AsyncMock(return_value=fake_proc(b"", b"auth expired", 1))):
        with pytest.raises(claude_cli.CliError, match="auth expired"):
            await claude_cli.run_capability("propose", "K", "d")


async def test_run_capability_timeout_is_504():
    p = fake_proc()
    p.communicate = AsyncMock(side_effect=asyncio.TimeoutError)
    with patch("claude_cli.asyncio.create_subprocess_exec", AsyncMock(return_value=p)):
        with pytest.raises(claude_cli.CliError) as e:
            await claude_cli.run_capability("propose", "K", "d")
    assert e.value.status == 504


def test_prompts_describe_proxy_and_secret_contract():
    gen = claude_cli.build_capability_prompt("generate", "K", "d", "spec", {"network": ["api.github.com"], "secrets": ["GH_TOKEN"]})
    for needle in ("urllib.request", "HTTPS_PROXY", "STANDARD LIBRARY ONLY", "EXACTLY that name", "os.environ"):
        assert needle in gen
    assert "NO network" not in gen and "currently offers none" not in gen
    prop = claude_cli.build_capability_prompt("propose", "K", "d")
    assert "plain public hostnames" in prop and "no IP addresses" in prop
    for needle in ("WebSearch", "untrusted DATA", "FREE_TIER", "UNOFFICIAL_API", "difficulty"):
        assert needle in prop
