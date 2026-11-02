"""Internal HTTP API (port 8092): Trello board create/delete on mongo-backup's global Trello account.

NOTE: javaapp no longer calls this (board lifecycle moved to the per-user TrelloApiClient; planning moved to
Relay runners). Kept only because removal was out of scope; it has no remaining caller.

Same process as the FastMCP server (started from mcp_server.py in a thread). Not in the Caddyfile and
no published port: reachable only as mongo-backup:8092 on the docker network. No auth — network
isolation is the only gate. Reuses the MCP tool functions directly — no logic is duplicated here.
"""
import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from trello_mcp.config import TRELLO_BASE, _auth

PORT = 8092


class BoardIn(BaseModel):
    name: str


app = FastAPI(title="HabitTracker internal API")


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


def start_in_thread() -> None:
    """Fail fast on missing token, then serve on 0.0.0.0:8092 in a daemon thread."""
    import threading
    import uvicorn
    server = uvicorn.Server(uvicorn.Config(app, host="0.0.0.0", port=PORT, log_level="info"))
    threading.Thread(target=server.run, daemon=True).start()
