"""Bug-survey dispatch logic — mirrors Kotlin BugSurveySessionStore / BugSurveyStartPolicy."""

from __future__ import annotations

import re
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Literal

TICKET_ID = re.compile(r"RB-\d{5}", re.IGNORECASE)

AgentMode = Literal["legacy_bug", "bug_survey"]

SHORT_CONFIRM_MARKERS = (
    "да",
    "у меня",
    "тоже",
    "ага",
    "same",
    "+1",
    "не работает",
    "не пашет",
)


def _key(player: str) -> str:
    return player.strip().lower()


@dataclass
class InboundMeta:
    directed_at_bot: bool = False
    reply_to_bot: bool = False
    continuation_with_bot: bool = False
    seconds_since_bot: int | None = None
    reply_to_player: str | None = None


@dataclass
class OpenTicket:
    ticket_id: str
    reporter: str
    status: str = "open"
    title: str | None = None


@dataclass
class BugSurveySession:
    player: str
    started_at_ms: int
    last_activity_at_ms: int
    ticket_id: str | None = None
    topic_hint: str | None = None
    participants: set[str] = field(default_factory=set)
    awaiting_global_responses: bool = False
    last_global_question: str | None = None
    last_global_asked_at_ms: int = 0

    def is_primary(self, name: str) -> bool:
        return self.player.lower() == name.strip().lower()

    def includes(self, name: str) -> bool:
        n = name.strip().lower()
        return self.is_primary(name) or any(p.lower() == n for p in self.participants)


class BugSurveySessionStore:
    """In-memory store with injectable clock (monotonic ms)."""

    def __init__(self, *, now_ms: Callable[[], int] | None = None) -> None:
        self._now_ms = now_ms or (lambda: int(time.time() * 1000))
        self._sessions: dict[str, BugSurveySession] = {}
        self._participant_index: dict[str, str] = {}

    def clear(self) -> None:
        self._sessions.clear()
        self._participant_index.clear()

    def now(self) -> int:
        return self._now_ms()

    def get(self, player: str) -> BugSurveySession | None:
        return self._sessions.get(_key(player))

    def is_active(self, player: str) -> bool:
        return self.find_for_player(player) is not None

    def find_for_player(self, player: str) -> BugSurveySession | None:
        k = _key(player)
        if k in self._sessions:
            return self._sessions[k]
        primary_key = self._participant_index.get(k)
        if primary_key is None:
            return None
        return self._sessions.get(primary_key)

    def find_global_inquiry_for_respondent(
        self,
        respondent: str,
        message: str,
        meta: InboundMeta,
        window_ms: int,
    ) -> BugSurveySession | None:
        if window_ms <= 0:
            return None
        now = self.now()
        candidates = [
            s
            for s in self._sessions.values()
            if s.awaiting_global_responses
            and now - s.last_global_asked_at_ms <= window_ms
            and not s.is_primary(respondent)
        ]
        if not candidates:
            return None
        linked = meta.reply_to_bot or meta.continuation_with_bot
        lower = message.strip().lower()
        short_confirm = len(lower) <= 60 and any(m in lower for m in SHORT_CONFIRM_MARKERS)
        if not linked and not short_confirm:
            return None
        return max(candidates, key=lambda s: s.last_global_asked_at_ms)

    def resolve_session(
        self,
        player: str,
        message: str,
        meta: InboundMeta,
        global_inquiry_window_ms: int,
    ) -> BugSurveySession | None:
        found = self.find_for_player(player)
        if found is not None:
            return found
        return self.find_global_inquiry_for_respondent(
            player,
            message,
            meta,
            global_inquiry_window_ms,
        )

    def open_or_touch(self, player: str) -> BugSurveySession:
        k = _key(player)
        now = self.now()
        existing = self._sessions.get(k)
        if existing is not None:
            existing.last_activity_at_ms = now
            self.add_participant(existing.player, existing.player)
            return existing
        session = BugSurveySession(
            player=player.strip(),
            started_at_ms=now,
            last_activity_at_ms=now,
        )
        self._sessions[k] = session
        self.add_participant(player, player)
        return session

    def add_participant(self, primary_reporter: str, participant: str) -> None:
        session = self._sessions.get(_key(primary_reporter))
        if session is None:
            return
        name = participant.strip()
        if not name:
            return
        session.participants.add(name)
        self._participant_index[_key(name)] = _key(primary_reporter)
        session.last_activity_at_ms = self.now()

    def mark_awaiting_global_responses(self, primary_reporter: str, question: str) -> BugSurveySession:
        session = self.open_or_touch(primary_reporter)
        session.awaiting_global_responses = True
        session.last_global_question = question.strip() or None
        session.last_global_asked_at_ms = self.now()
        session.last_activity_at_ms = session.last_global_asked_at_ms
        return session

    def bind_ticket(self, player: str, ticket_id: str, topic_hint: str | None = None) -> None:
        session = self.find_for_player(player) or self._sessions.get(_key(player))
        if session is None:
            return
        session.ticket_id = ticket_id.strip()
        session.topic_hint = (topic_hint or "").strip() or None
        session.last_activity_at_ms = self.now()

    def close(self, player: str, _reason: str = "") -> bool:
        session = self.find_for_player(player)
        if session is None:
            return False
        k = _key(session.player)
        removed = self._sessions.pop(k, None)
        for p in session.participants:
            self._participant_index.pop(_key(p), None)
        self._participant_index.pop(k, None)
        return removed is not None


def should_start_survey(
    *,
    survey_enabled: bool,
    player: str,
    message: str,
    open_ticket: OpenTicket | None,
    investigation: BugSurveySession | None,
    store: BugSurveySessionStore,
) -> bool:
    if not survey_enabled:
        return False
    if investigation is not None:
        return True
    if store.is_active(player):
        return True
    if open_ticket is not None:
        return True
    if TICKET_ID.search(message):
        return True
    return False


@dataclass(frozen=True)
class BugDispatchDecision:
    use_survey: bool
    primary: str
    witness: bool
    agent_mode: AgentMode
    investigation: BugSurveySession | None = None


def decide_bug_dispatch(
    *,
    store: BugSurveySessionStore,
    player: str,
    message: str,
    meta: InboundMeta,
    open_ticket: OpenTicket | None,
    survey_enabled: bool = True,
    global_inquiry_window_sec: int = 300,
) -> BugDispatchDecision:
    window_ms = max(30, min(global_inquiry_window_sec, 900)) * 1000
    investigation = store.resolve_session(player, message, meta, window_ms)
    resolved_open = open_ticket
    if resolved_open is None and investigation is not None and investigation.ticket_id:
        resolved_open = OpenTicket(
            ticket_id=investigation.ticket_id,
            reporter=investigation.player,
        )

    use_survey = should_start_survey(
        survey_enabled=survey_enabled,
        player=player,
        message=message,
        open_ticket=resolved_open,
        investigation=investigation,
        store=store,
    )
    primary = investigation.player if investigation else player
    witness = investigation is not None and not investigation.is_primary(player)
    mode: AgentMode = "bug_survey" if use_survey else "legacy_bug"
    return BugDispatchDecision(
        use_survey=use_survey,
        primary=primary,
        witness=witness,
        agent_mode=mode,
        investigation=investigation,
    )


def apply_bug_dispatch(
    store: BugSurveySessionStore,
    decision: BugDispatchDecision,
    player: str,
) -> BugSurveySession | None:
    if not decision.use_survey:
        return None
    session = store.open_or_touch(decision.primary)
    if not player.lower() == decision.primary.lower():
        store.add_participant(decision.primary, player)
    return store.get(decision.primary)


def enrich_ticket_append(
    agent_text: str | None,
    dialog: str,
    trigger_message: str | None,
) -> str:
    parts: list[str] = []
    if trigger_message and trigger_message.strip():
        parts.append(f"**Игрок:** {trigger_message.strip()}")
    if agent_text and agent_text.strip():
        parts.append(agent_text.strip())
    if dialog.strip():
        parts.append(f"**Диалог:**\n{dialog.strip()}")
    if parts:
        return "\n\n".join(parts)
    return (agent_text or "").strip()
