"""Длинные сценарные тесты bug-survey: multi-player, global inquiry, legacy vs survey."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from bug_survey import (
    BugDispatchDecision,
    BugSurveySessionStore,
    InboundMeta,
    OpenTicket,
    apply_bug_dispatch,
    decide_bug_dispatch,
    enrich_ticket_append,
)

SCENARIOS_PATH = Path(__file__).with_name("bug_survey_scenarios.json")


class FakeClock:
    def __init__(self, start_ms: int = 1_700_000_000_000) -> None:
        self._ms = start_ms

    def now_ms(self) -> int:
        return self._ms

    def advance_sec(self, seconds: int) -> None:
        self._ms += seconds * 1000


def _meta(data: dict[str, Any] | None) -> InboundMeta:
    data = data or {}
    return InboundMeta(
        directed_at_bot=bool(data.get("directed_at_bot", False)),
        reply_to_bot=bool(data.get("reply_to_bot", False)),
        continuation_with_bot=bool(data.get("continuation_with_bot", False)),
        seconds_since_bot=data.get("seconds_since_bot"),
        reply_to_player=data.get("reply_to_player"),
    )


def _open_ticket(data: dict[str, Any] | None) -> OpenTicket | None:
    if not data:
        return None
    return OpenTicket(
        ticket_id=data["ticket_id"],
        reporter=data.get("reporter", data.get("player", "")),
        title=data.get("title"),
    )


def _dispatch(
    store: BugSurveySessionStore,
    *,
    player: str,
    message: str,
    meta: InboundMeta | None = None,
    open_ticket: OpenTicket | None = None,
    global_window_sec: int = 300,
) -> BugDispatchDecision:
    return decide_bug_dispatch(
        store=store,
        player=player,
        message=message,
        meta=meta or InboundMeta(),
        open_ticket=open_ticket,
        survey_enabled=True,
        global_inquiry_window_sec=global_window_sec,
    )


def _run_scenario_step(
    store: BugSurveySessionStore,
    clock: FakeClock,
    step: dict[str, Any],
) -> BugDispatchDecision | None:
    event = step["event"]

    if event == "advance_time_sec":
        clock.advance_sec(int(step["seconds"]))
        return None

    if event == "setup_survey_with_ticket":
        primary = step["primary"]
        store.open_or_touch(primary)
        store.bind_ticket(primary, step["ticket_id"], step.get("topic"))
        return None

    if event == "agent_create_ticket":
        store.open_or_touch(step["player"])
        store.bind_ticket(step["player"], step["ticket_id"], step.get("topic"))
        return None

    if event == "agent_global_message":
        store.mark_awaiting_global_responses(step["primary"], step["message"])
        return None

    if event == "close_survey":
        store.close(step["primary"], step.get("reason", ""))
        return None

    if event == "assert_participants":
        session = store.get(step["primary"])
        assert session is not None, f"no session for {step['primary']}"
        expected = set(step["participants"])
        assert expected <= session.participants, (
            f"expected participants {expected}, got {session.participants}"
        )
        return None

    if event == "assert_ticket_append":
        text = enrich_ticket_append(
            step.get("agent_text"),
            "\n".join(step.get("dialog_lines", [])),
            step.get("trigger_message"),
        )
        for fragment in step.get("must_contain", []):
            assert fragment in text, f"missing {fragment!r} in:\n{text}"
        return None

    if event == "player_message":
        meta = _meta(step.get("meta"))
        open_ticket = _open_ticket(step.get("open_ticket"))
        decision = _dispatch(
            store,
            player=step["player"],
            message=step["message"],
            meta=meta,
            open_ticket=open_ticket,
        )
        if decision.use_survey:
            apply_bug_dispatch(store, decision, step["player"])
        return decision

    raise AssertionError(f"unknown event {event!r}")


def _assert_expect(
    decision: BugDispatchDecision | None,
    expect: dict[str, Any],
    store: BugSurveySessionStore,
) -> None:
    assert decision is not None, "expected player_message step with decision"
    if "use_survey" in expect:
        assert decision.use_survey is expect["use_survey"], (
            f"use_survey: expected {expect['use_survey']}, got {decision.use_survey}"
        )
    if "agent_mode" in expect:
        assert decision.agent_mode == expect["agent_mode"]
    if "primary" in expect:
        assert decision.primary.lower() == expect["primary"].lower()
    if "witness" in expect:
        assert decision.witness is expect["witness"]
    if "session_ticket" in expect:
        session = store.get(decision.primary)
        assert session is not None
        assert session.ticket_id == expect["session_ticket"]
    if "participants_include" in expect:
        session = store.get(decision.primary)
        assert session is not None
        for name in expect["participants_include"]:
            assert name in session.participants
    if expect.get("investigation_resolved") is False:
        assert decision.investigation is None


def load_scenarios() -> list[dict[str, Any]]:
    with SCENARIOS_PATH.open(encoding="utf-8") as f:
        return json.load(f)


@pytest.fixture
def store_and_clock() -> tuple[BugSurveySessionStore, FakeClock]:
    clock = FakeClock()
    store = BugSurveySessionStore(now_ms=clock.now_ms)
    return store, clock


class TestBugSurveyScenariosFromJson:
    """Параметризованный прогон bug_survey_scenarios.json — полные multi-step flows."""

    @pytest.mark.parametrize("scenario", load_scenarios(), ids=lambda s: s["id"])
    def test_scenario_end_to_end(self, scenario: dict[str, Any]) -> None:
        clock = FakeClock()
        store = BugSurveySessionStore(now_ms=clock.now_ms)

        for step in scenario["steps"]:
            decision = _run_scenario_step(store, clock, step)
            if "expect" in step:
                _assert_expect(decision, step["expect"], store)


class TestNewReportVsSurvey:
    """Явные unit-тесты на границу legacy bug ↔ bug-survey."""

    def test_first_vague_report_without_ticket_is_legacy(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        d = _dispatch(store, player="GrocerMC", message="у меня баг с rtp")
        assert d.use_survey is False
        assert d.agent_mode == "legacy_bug"

    def test_second_message_with_open_ticket_enters_survey(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        store.bind_ticket("GrocerMC", "RB-00001", "rtp")
        d = _dispatch(
            store,
            player="GrocerMC",
            message="на survival жму /rtp",
            meta=InboundMeta(reply_to_bot=True),
            open_ticket=OpenTicket("RB-00001", "GrocerMC"),
        )
        assert d.use_survey is True
        assert d.agent_mode == "bug_survey"
        assert d.witness is False

    def test_survey_disabled_always_legacy(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        d = decide_bug_dispatch(
            store=store,
            player="GrocerMC",
            message="rtp",
            meta=InboundMeta(),
            open_ticket=OpenTicket("RB-00001", "GrocerMC"),
            survey_enabled=False,
        )
        assert d.use_survey is False
        assert d.agent_mode == "legacy_bug"


class TestGlobalInquiryMultiPlayer:
    """Расширение scope: sendglobalmessage → witnesses."""

    def test_global_opens_session_and_awaits_responses(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        session = store.mark_awaiting_global_responses("GrocerMC", "у кого rtp не пашет?")
        assert session.awaiting_global_responses is True
        assert session.last_global_question == "у кого rtp не пашет?"

    def test_witness_linked_via_reply_to_bot(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        store.mark_awaiting_global_responses("GrocerMC", "у кого rtp?")
        d = _dispatch(
            store,
            player="Koxae",
            message="да у меня тоже",
            meta=InboundMeta(reply_to_bot=True, continuation_with_bot=True),
        )
        assert d.use_survey is True
        assert d.witness is True
        assert d.primary == "GrocerMC"
        apply_bug_dispatch(store, d, "Koxae")
        assert "Koxae" in store.get("GrocerMC").participants

    def test_witness_short_da_without_reply_meta(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        store.mark_awaiting_global_responses("GrocerMC", "у кого баг?")
        d = _dispatch(store, player="Koxae", message="да")
        assert d.use_survey is True
        assert d.witness is True

    def test_unrelated_message_not_linked(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        store.mark_awaiting_global_responses("GrocerMC", "у кого rtp?")
        d = _dispatch(store, player="RandomGuy", message="лол кек чебурек")
        assert d.investigation is None
        assert d.use_survey is False

    def test_expired_global_window_no_auto_link(self, store_and_clock: tuple) -> None:
        store, clock = store_and_clock
        store.mark_awaiting_global_responses("GrocerMC", "у кого rtp?")
        clock.advance_sec(400)
        d = _dispatch(
            store,
            player="Koxae",
            message="да у меня тоже",
            meta=InboundMeta(reply_to_bot=True),
        )
        assert d.investigation is None
        assert d.use_survey is False

    def test_two_witnesses_same_investigation(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        store.open_or_touch("GrocerMC")
        store.bind_ticket("GrocerMC", "RB-00099", "farm")
        store.mark_awaiting_global_responses("GrocerMC", "у кого кокос не растёт?")

        for witness, msg in [
            ("Koxae", "да не растёт"),
            ("yarostuf", "у меня тоже на survival"),
        ]:
            d = _dispatch(
                store,
                player=witness,
                message=msg,
                meta=InboundMeta(reply_to_bot=True),
            )
            assert d.witness is True
            apply_bug_dispatch(store, d, witness)

        session = store.get("GrocerMC")
        assert session is not None
        assert {"GrocerMC", "Koxae", "yarostuf"} <= session.participants


class TestTicketContextEnrichment:
    """Диалог в Discord-тикете — enrich_ticket_append."""

    def test_append_includes_player_agent_and_dialog(self) -> None:
        text = enrich_ticket_append(
            agent_text="Koxae подтвердил баг на survival",
            dialog="игрок: rtp не работает\nскорен (глобал): у кого?\nигрок: да",
            trigger_message="у меня тоже",
        )
        assert "**Игрок:** у меня тоже" in text
        assert "Koxae подтвердил" in text
        assert "**Диалог:**" in text
        assert "скорен (глобал)" in text

    def test_append_agent_only_when_no_dialog(self) -> None:
        text = enrich_ticket_append("только summary", "", None)
        assert text == "только summary"

    def test_long_witness_thread_in_ticket(self) -> None:
        dialog = "\n".join(
            [
                "игрок: помогитя баг",
                "скорен (личка → Koxae): че сломалось?",
                "игрок: не растёт кокос",
                "скорен (глобал): у кого кокос не растёт?",
                "игрок: да на survival",
                "игрок: yarostuf: у меня тоже",
            ],
        )
        text = enrich_ticket_append(
            "три игрока подтвердили проблему с кокосом на survival",
            dialog,
            "yarostuf: у меня тоже",
        )
        assert "yarostuf" in text
        assert "кокос" in text
        assert len(text) > 120


class TestSessionLifecycle:
    """Закрытие survey и повторный репорт."""

    def test_close_clears_participant_index(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        store.open_or_touch("GrocerMC")
        store.add_participant("GrocerMC", "Koxae")
        assert store.find_for_player("Koxae") is not None
        store.close("GrocerMC", "ticket_closed")
        assert store.find_for_player("Koxae") is None
        assert store.find_for_player("GrocerMC") is None

    def test_after_close_new_report_is_legacy(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        store.open_or_touch("GrocerMC")
        store.bind_ticket("GrocerMC", "RB-00001", "rtp")
        store.close("GrocerMC", "ticket_closed")
        d = _dispatch(store, player="GrocerMC", message="новый баг с палкой")
        assert d.use_survey is False


class TestMentionTicketId:
    def test_rb_mention_starts_survey_without_open_ticket(self, store_and_clock: tuple) -> None:
        store, _ = store_and_clock
        d = _dispatch(store, player="GrocerMC", message="про RB-00042 ещё вопрос")
        assert d.use_survey is True
        assert d.agent_mode == "bug_survey"


class TestScenariosFileIntegrity:
    def test_scenarios_json_loads_and_has_long_flows(self) -> None:
        scenarios = load_scenarios()
        assert len(scenarios) >= 8
        multi_step = [s for s in scenarios if len(s.get("steps", [])) >= 4]
        assert len(multi_step) >= 3, "expected several multi-step scenarios"
        for s in scenarios:
            assert "id" in s
            assert "steps" in s
            assert len(s["steps"]) >= 1
