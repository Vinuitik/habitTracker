import os
import sys
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
os.environ.setdefault("TRELLO_API_KEY", "test-key")
os.environ.setdefault("TRELLO_TOKEN", "test-token")

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
