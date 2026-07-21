"""Pydantic input models for the batch tools, and the LLM-facing field docs.

Typed so the LLM sees a clear schema with sensible defaults.
"""
from pydantic import BaseModel, Field

_AFTER_DOC = (
    "Cards that must be DONE before this one starts — the dependency graph. Declare it and the "
    "server does the topological sort and scheduling; never try to order tasks yourself, and never "
    "encode order by listing cards in sequence. Two forms: a bare slug ('user-model') refers to a "
    "card in the same list, a full handle ('frm/kpi/kpi-model') to one elsewhere. Do NOT add edges "
    "to cards that are already done — a finished dependency constrains nothing and is dropped."
)
_EST_DOC = (
    "Load weight, NOT duration. A card is one atomic step you can finish in a single sitting, so "
    "this should be 1 (the default — just omit it). Setting est>1 is a confession that the card is "
    "really several cards: expect the tool to tell you to split it. Nothing here schedules a card "
    "across multiple days."
)
_FEATURE_DOC = (
    "Feature slug this card belongs to, e.g. 'auth'. Defaults to the list name. Stored on the card "
    "itself because cards get moved into dated lists, which destroys the list-as-feature grouping."
)
_IMPORTANCE_DOC = (
    "MoSCoW priority, 1-3: 3 = Must (critical, do first), 2 = Should (default — omit for this), "
    "1 = Could (nice-to-have, whenever). Set the extremes confidently and leave the middle at 2. "
    "This only re-ranks which of the currently-unblocked cards get scheduled earlier; it NEVER "
    "overrides dependencies — a prerequisite is always scheduled before the card that needs it, "
    "whatever its importance."
)


class NewCard(BaseModel):
    title: str
    description: str = ""
    labels: list[str] = Field(default_factory=list, description="Existing label names on the board; unknown names are skipped")
    due: str | None = Field(default=None, description="ISO 8601 due date, or omit for none")
    checklist: list[str] = Field(default_factory=list)
    after: list[str] = Field(default_factory=list, description=_AFTER_DOC + " May reference cards created in this same batch.")
    est: float | None = Field(default=None, description=_EST_DOC)
    importance: int | None = Field(default=None, description=_IMPORTANCE_DOC)


class CardUpdate(BaseModel):
    handle: str
    title: str | None = None
    description: str | None = None
    labels: list[str] | None = Field(default=None, description="Replaces all labels when given")
    due: str | None = Field(default=None, description="ISO 8601 to set, the string 'null' to clear, omit to leave unchanged")
    checklist: list[str] | None = Field(default=None, description="Replaces the checklist when given")
    after: list[str] | None = Field(default=None, description=_AFTER_DOC + " Replaces all edges when given; pass [] to clear.")
    est: float | None = Field(default=None, description=_EST_DOC)
    feature: str | None = Field(default=None, description=_FEATURE_DOC)
    importance: int | None = Field(default=None, description=_IMPORTANCE_DOC)


class NewList(BaseModel):
    board: str = Field(description="Board name or slug")
    name: str = Field(description="List name. Use a topic name, or an ISO date (YYYY-MM-DD) for a day list.")
    pos: str = "bottom"


class CardMove(BaseModel):
    handle: str
    to_list: str = Field(description="Destination list name or slug")
    pos: str = "bottom"


class CardSplit(BaseModel):
    handle: str = Field(description="Card to split")
    into: list[str] = Field(description="Titles of the replacement cards, in dependency order")
    chain: bool = Field(default=True, description="True: each part depends on the previous. False: parts are independent.")
