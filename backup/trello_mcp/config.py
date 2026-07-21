"""Environment, domain constants, the shared FastMCP instance, and Trello auth.

Everything else in the package imports from here. This module imports nothing from the
package, so it is the root of the import graph.
"""
import os
import re

from fastmcp import FastMCP

try:
    from fastmcp.exceptions import ToolError
except Exception:  # pragma: no cover - older/newer fastmcp
    class ToolError(Exception):
        pass

# ── Secrets / environment ────────────────────────────────────────────────────────
TRELLO_API_KEY = os.getenv("TRELLO_API_KEY", "")
TRELLO_TOKEN = os.getenv("TRELLO_TOKEN", "")
TRELLO_CRON_BOARD_ID = os.getenv("TRELLO_CRON_BOARD_ID", "")
TRELLO_CRON_BOARD_NAME = os.getenv("TRELLO_CRON_BOARD_NAME", "")
TRELLO_BASE = "https://api.trello.com/1"

# ── Domain constants ─────────────────────────────────────────────────────────────
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
DEFAULT_EST = 1.0
META_LIST = "_meta"       # holds the STATE card; excluded from every read and the scheduler
STATE_CARD = "STATE"
DONE_LABEL = "done"
PARKED_LABEL = "parked"   # excluded from scheduling until unparked; not done, just not now
DEFAULT_PACE = 2.0        # cards/day when no deadline is given
DEFAULT_IMPORTANCE = 2    # MoSCoW: 3=Must, 2=Should, 1=Could. Absent → Should.
IMPORTANCE_MIN, IMPORTANCE_MAX = 1, 3

# ── Shared server instance (tool modules register on this) ─────────────────────────
mcp = FastMCP("HabitTracker Trello")


def _auth() -> dict:
    return {"key": TRELLO_API_KEY, "token": TRELLO_TOKEN}
