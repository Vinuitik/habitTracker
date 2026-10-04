"""Internal HTTP API (port 8092) so the Java app can call Trello functions directly.

Same process as the FastMCP server (started from mcp_server.py in a thread). Not in the Caddyfile and
no published port: reachable only as mongo-backup:8092 on the docker network. No auth — network
isolation is the only gate. Reuses the MCP tool functions directly — no logic is duplicated here.
"""
import asyncio

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

import claude_cli
from trello_mcp.api import _get
from trello_mcp.config import DEFAULT_PACE, TRELLO_BASE, ToolError, _auth
from trello_mcp.tools_planning import apply_schedule

PORT = 8092
_plan_lock = asyncio.Lock()  # single-flight: one `claude -p` at a time


class BoardIn(BaseModel):
    name: str


class PlanIn(BaseModel):
    boardId: str
    description: str


class ApplyIn(BaseModel):
    boardId: str
    deadline: str | None = None
    pace: float = DEFAULT_PACE


app = FastAPI(title="HabitTracker internal API")


async def _board_name(board_id: str) -> str:
    async with httpx.AsyncClient() as client:
        try:
            return (await _get(client, f"/boards/{board_id}", fields="name"))["name"]
        except httpx.HTTPStatusError as e:
            raise HTTPException(404 if e.response.status_code in (400, 404) else 502, "board lookup failed")


@app.post("/internal/boards")
async def create_board(body: BoardIn) -> dict:
    # defaultLists/defaultLabels off: Completed/Delayed lists, done/parked labels and _meta/STATE are
    # created lazily by the planning tools on first use, so an empty board is already valid.
    async with httpx.AsyncClient() as client:
        r = await client.post(f"{TRELLO_BASE}/boards", params={
            **_auth(), "name": body.name, "defaultLists": "false", "defaultLabels": "false"})
    if r.status_code != 200:
        raise HTTPException(502, f"Trello create board failed: {r.status_code}")
    return {"boardId": r.json()["id"]}


@app.delete("/internal/boards/{board_id}")
async def delete_board(board_id: str) -> dict:
    # The ONE intentional hard delete in the Trello integration: irreversible (everything else
    # archives). Callers (Java two-step delete) are responsible for confirming first.
    async with httpx.AsyncClient() as client:
        r = await client.delete(f"{TRELLO_BASE}/boards/{board_id}", params=_auth())
    if r.status_code == 404:
        raise HTTPException(404, "board not found")
    if r.status_code != 200:
        raise HTTPException(502, f"Trello delete board failed: {r.status_code}")
    return {"deleted": board_id}


@app.post("/internal/agent/plan")
async def agent_plan(body: PlanIn) -> dict:
    if _plan_lock.locked():
        raise HTTPException(409, "a plan request is already running")
    async with _plan_lock:
        name = await _board_name(body.boardId)
        try:
            return await claude_cli.run_plan(name, body.description)
        except claude_cli.CliError as e:
            raise HTTPException(e.status, str(e))


@app.post("/internal/agent/apply")
async def agent_apply(body: ApplyIn) -> dict:
    name = await _board_name(body.boardId)
    try:
        return await apply_schedule(board=name, deadline=body.deadline, pace=body.pace)
    except ToolError as e:
        raise HTTPException(422, str(e))


def start_in_thread() -> None:
    """Fail fast on missing token, then serve on 0.0.0.0:8092 in a daemon thread."""
    import threading
    import uvicorn
    server = uvicorn.Server(uvicorn.Config(app, host="0.0.0.0", port=PORT, log_level="info"))
    threading.Thread(target=server.run, daemon=True).start()
