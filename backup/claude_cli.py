"""Wrapper around the Claude Code CLI (`claude -p`) used by the internal planning endpoint.

The CLI runs headless against the local Trello MCP (http://localhost:8091/mcp, no Caddy token) with
only the planning tools allowed — notably NOT apply_schedule, so a plan can never move cards.
Auth is the user's subscription, via the mounted ~/.claude (see FLOWS_mcp.md → Internal API).
"""
import asyncio
import json
import os

MCP_URL = os.getenv("CLAUDE_MCP_URL", "http://localhost:8091/mcp")
MCP_SERVER_NAME = "trello"
CLAUDE_BIN = os.getenv("CLAUDE_BIN", "claude")
MAX_TURNS = int(os.getenv("CLAUDE_MAX_TURNS", "25"))
TIMEOUT_S = int(os.getenv("CLAUDE_TIMEOUT_S", "300"))

# apply_schedule and every card-mutating tool beyond creation are deliberately absent.
ALLOWED_TOOLS = [f"mcp__{MCP_SERVER_NAME}__{t}" for t in (
    "get_state", "describe_graph", "describe_board", "get_cards",
    "create_lists", "create_cards", "propose_schedule",
)]


class CliError(Exception):
    """CLI failed (non-zero exit, bad output). `status` is the HTTP status to surface."""
    def __init__(self, msg: str, status: int = 502):
        super().__init__(msg)
        self.status = status


def build_prompt(board_name: str, description: str) -> str:
    return (
        f"Plan the work below on the Trello board named '{board_name}' using the trello MCP tools.\n"
        "Follow exactly this order: get_state, describe_graph, then create_cards (atomic cards, each "
        "with importance, est and `after` dependency edges; create_lists first if needed), then "
        "propose_schedule. NEVER apply the schedule; you have no tool for it.\n"
        "When done, reply with ONLY one JSON object, no prose and no code fences: "
        '{"cards": [<created cards as returned by create_cards>], "proposal": <propose_schedule result>}.\n\n'
        f"Work to plan:\n{description}"
    )


def build_cmd(prompt: str) -> list[str]:
    mcp_config = json.dumps({"mcpServers": {MCP_SERVER_NAME: {"type": "http", "url": MCP_URL}}})
    return [
        CLAUDE_BIN, "-p", prompt,
        "--output-format", "json",
        "--mcp-config", mcp_config, "--strict-mcp-config",
        "--allowedTools", ",".join(ALLOWED_TOOLS),
        "--tools", "",  # no built-ins (Bash/Read/...): MCP tools only
        "--max-turns", str(MAX_TURNS),
    ]


def parse_result(stdout: str) -> dict:
    """CLI json envelope -> the {cards, proposal} object the model was told to emit."""
    try:
        envelope = json.loads(stdout)
    except json.JSONDecodeError:
        raise CliError("claude CLI returned non-JSON output")
    if envelope.get("is_error"):
        raise CliError(f"claude CLI error: {str(envelope.get('result', ''))[:300]}")
    text = str(envelope.get("result", "")).strip()
    if text.startswith("```"):
        text = text.strip("`").removeprefix("json").strip()
    start, end = text.find("{"), text.rfind("}")
    try:
        out = json.loads(text[start:end + 1])
    except (json.JSONDecodeError, ValueError):
        raise CliError("claude CLI result was not the expected JSON object")
    if not isinstance(out, dict) or "cards" not in out or "proposal" not in out:
        raise CliError("claude CLI result missing 'cards'/'proposal'")
    return out


async def run_plan(board_name: str, description: str) -> dict:
    proc = await asyncio.create_subprocess_exec(
        *build_cmd(build_prompt(board_name, description)),
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=TIMEOUT_S)
    except asyncio.TimeoutError:
        proc.kill()
        await proc.wait()
        raise CliError(f"claude CLI timed out after {TIMEOUT_S}s", status=504)
    if proc.returncode != 0:
        raise CliError(f"claude CLI exited {proc.returncode}: {stderr.decode(errors='replace')[-300:]}")
    return parse_result(stdout.decode(errors="replace"))
