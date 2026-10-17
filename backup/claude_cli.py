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


def _extract_object(stdout: str) -> dict:
    """CLI json envelope -> the single JSON object the model was told to emit."""
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
    if not isinstance(out, dict):
        raise CliError("claude CLI result was not the expected JSON object")
    return out


def parse_result(stdout: str) -> dict:
    """CLI json envelope -> the {cards, proposal} object the model was told to emit."""
    out = _extract_object(stdout)
    if "cards" not in out or "proposal" not in out:
        raise CliError("claude CLI result missing 'cards'/'proposal'")
    return out


async def _run_cli(cmd: list[str]) -> str:
    proc = await asyncio.create_subprocess_exec(
        *cmd,
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
    return stdout.decode(errors="replace")


async def run_plan(board_name: str, description: str) -> dict:
    return parse_result(await _run_cli(build_cmd(build_prompt(board_name, description))))


# ── KPI capability agent (M8): proposal + code generation ────────────────────
# Pure text-in/text-out: NO MCP servers (empty strict config) and NO built-in tools, so the model
# cannot touch Trello, files or the network. Its output is only ever data: a proposal the user must
# approve, and python source that Java runs exclusively inside the sandbox sidecar.

CAPABILITY_MODES = ("propose", "generate")
CAPABILITY_MAX_TURNS = 3
MAX_SOURCE_BYTES = 64 * 1024  # sandbox's own source cap; fail early instead of at the sidecar

_SANDBOX_FACTS = (
    "Execution environment for the script: python 3.12 STANDARD LIBRARY ONLY (no requests, no pip), "
    "read-only filesystem, ~30s timeout. A module-level string constant "
    'TARGET_DATE = "YYYY-MM-DD" is prepended before your code. The script must print the KPI '
    "value for TARGET_DATE as a plain number on the LAST line of stdout (extra earlier lines are ignored).\n"
    "NETWORK: by default there is none. The ONLY network access is to the exact hostnames listed in the approved "
    "declaredCapabilities.network (https on 443 or http on 80, any path on those hosts), reached through a "
    "mandatory HTTP proxy that is already configured in the HTTPS_PROXY/HTTP_PROXY environment variables. Use "
    "urllib.request ONLY: urlopen() picks the proxy (and its credentials) up from the environment automatically, "
    "so do not set proxies yourself, do not print or log them, and do NOT use http.client, socket, or ssl "
    "directly (they bypass the proxy and will fail). Any other host, an IP address, or a non-80/443 port is "
    "denied (you will see HTTP 403 / 'Tunnel connection failed'). Always pass timeout=15 to urlopen. "
    "Example: urllib.request.urlopen(urllib.request.Request('https://api.example.com/v1/x', "
    "headers={'Authorization': 'Bearer ' + os.environ['API_TOKEN']}), timeout=15).\n"
    "SECRETS: each name in the approved declaredCapabilities.secrets is available as an environment variable "
    "with EXACTLY that name (os.environ['NAME']). Never print, log or return a secret. No other secrets exist."
)


def build_capability_prompt(mode: str, kpi_name: str, description: str, proposal: str | None = None,
                            declared: dict | None = None, previous_code: str | None = None,
                            previous_error: str | None = None) -> str:
    if mode == "propose":
        return (
            "You design automatic data capture for a personal habit-tracker KPI.\n"
            f"KPI name: {kpi_name}\nUser wants this captured automatically: {description}\n\n"
            f"{_SANDBOX_FACTS}\n"
            "Write a concrete proposal: the data-source strategy and exactly what the script would need "
            "to touch. Be TRUTHFUL and MINIMAL in declaredCapabilities: list every network host and every secret the "
            "script would genuinely need, and nothing more; the user's approval grants exactly that list. "
            "network entries must be plain public hostnames only (e.g. api.github.com): no IP addresses, no "
            "ports, no wildcards, no URLs, nothing internal (localhost, *.local, *.internal). secrets entries "
            "are environment-variable style names the user will fill in (e.g. GITHUB_TOKEN: letters, digits, "
            "underscore). Prefer a source that needs no secret when one exists. platform is SERVER for code "
            "that runs on the server, WINDOWS or ANDROID if the data only exists on a user device.\n"
            "Reply with ONLY one JSON object, no prose, no code fences: "
            '{"proposal": "<2-6 sentence plain-language spec>", "platform": "SERVER|WINDOWS|ANDROID", '
            '"declaredCapabilities": {"network": [<hostnames>], "secrets": [<secret names>]}}'
        )
    prompt = (
        "Write the python script for this approved KPI capture spec. Implement ONLY what the spec "
        "approves; do not access anything beyond its declaredCapabilities.\n"
        f"KPI name: {kpi_name}\nUser request: {description}\n"
        f"Approved proposal: {proposal}\nApproved declaredCapabilities: {json.dumps(declared or {})}\n\n"
        f"{_SANDBOX_FACTS}\n"
    )
    if previous_error:
        prompt += (
            "\nA previous version FAILED its dry run. Fix it.\n"
            f"Previous code:\n{previous_code or '(none)'}\n"
            f"Failure:\n{previous_error}\n"
        )
    prompt += 'Reply with ONLY one JSON object, no prose, no code fences: {"sourceCode": "<full python source>"}'
    return prompt


def build_capability_cmd(prompt: str) -> list[str]:
    return [
        CLAUDE_BIN, "-p", prompt,
        "--output-format", "json",
        "--mcp-config", json.dumps({"mcpServers": {}}), "--strict-mcp-config",
        "--tools", "",  # no built-ins and no MCP tools: pure text generation
        "--max-turns", str(CAPABILITY_MAX_TURNS),
    ]


def parse_capability_result(mode: str, stdout: str) -> dict:
    out = _extract_object(stdout)
    if mode == "propose":
        if not isinstance(out.get("proposal"), str) or not out["proposal"].strip():
            raise CliError("claude CLI result missing 'proposal'")
        platform = str(out.get("platform", "SERVER")).upper()
        declared = out.get("declaredCapabilities") or {}
        if not isinstance(declared, dict):
            raise CliError("claude CLI result had invalid 'declaredCapabilities'")
        norm = {}
        for key in ("network", "secrets"):
            vals = declared.get(key) or []
            if not isinstance(vals, list):
                raise CliError(f"claude CLI result had invalid declaredCapabilities.{key}")
            norm[key] = [str(v) for v in vals]
        return {"proposal": out["proposal"].strip(), "platform": platform, "declaredCapabilities": norm}
    code = out.get("sourceCode")
    if not isinstance(code, str) or not code.strip():
        raise CliError("claude CLI result missing 'sourceCode'")
    code = code.strip()
    if code.startswith("```"):  # model put fences inside the JSON string anyway
        code = code.strip("`").removeprefix("python").strip()
    if len(code.encode()) > MAX_SOURCE_BYTES:
        raise CliError("generated sourceCode exceeds 64KB")
    return {"sourceCode": code}


async def run_capability(mode: str, kpi_name: str, description: str, proposal: str | None = None,
                         declared: dict | None = None, previous_code: str | None = None,
                         previous_error: str | None = None) -> dict:
    prompt = build_capability_prompt(mode, kpi_name, description, proposal, declared,
                                     previous_code, previous_error)
    return parse_capability_result(mode, await _run_cli(build_capability_cmd(prompt)))
