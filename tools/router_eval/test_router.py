"""pytest: offline parser + optional live OpenRouter eval."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from router import RouterInput, classify, parse_router_json, router_input_from_dict

CASES_PATH = Path(__file__).with_name("cases.json")


class TestParseRouterJson:
    def test_plain_json(self) -> None:
        r = parse_router_json('{"intent":"chat","confidence":0.9,"reason":"скорен"}')
        assert r.intent == "chat"
        assert r.confidence == 0.9

    def test_bug_legacy_wire_name(self) -> None:
        r = parse_router_json('{"intent":"bug_new","confidence":0.9,"reason":"rtp"}')
        assert r.intent == "bug"

    def test_fenced_json(self) -> None:
        raw = '```json\n{"intent":"skip","confidence":0.8,"reason":"флуд"}\n```'
        r = parse_router_json(raw)
        assert r.intent == "skip"


class TestRouterInputPayload:
    def test_chat_allowed_from_exclamation(self) -> None:
        inp = RouterInput(player="g", message="скорен", raw_text="!скорен")
        assert inp.resolved_chat_allowed() is True

    def test_payload_no_scenario_hint(self) -> None:
        inp = RouterInput(
            player="g",
            message="ку скорен",
            raw_text="!ку скорен",
            directed_at_bot=True,
            open_ticket_id="RB-00001",
        )
        payload = inp.to_user_content()
        assert "active_scenario_hint" not in payload


class TestCasesFile:
    def test_cases_valid(self) -> None:
        with CASES_PATH.open(encoding="utf-8") as f:
            cases = json.load(f)
        assert len(cases) >= 30
        intents = {"skip", "chat", "bug"}
        for case in cases:
            assert "id" in case
            assert "expected" in case
            assert case["expected"] in intents
            assert "player" in case["input"]
            assert "message" in case["input"]


@pytest.mark.live
def test_live_single_skip() -> None:
    if not os.environ.get("OPENROUTER_API_KEY"):
        pytest.skip("OPENROUTER_API_KEY not set")
    r = classify(RouterInput(player="gros", message="че как все"))
    assert r.intent in {"skip", "chat", "bug"}
    assert r.raw.strip() != ""


@pytest.mark.live
def test_live_full_suite() -> None:
    if not os.environ.get("OPENROUTER_API_KEY"):
        pytest.skip("OPENROUTER_API_KEY not set")

    with CASES_PATH.open(encoding="utf-8") as f:
        cases = json.load(f)

    wrong = []
    for case in cases:
        inp = router_input_from_dict(case["input"])
        expected = case["expected"]
        r = classify(inp)
        if r.intent != expected or (not r.raw.strip() and r.model != "heuristic"):
            acceptable = case.get("acceptable", [])
            if acceptable and r.intent in acceptable and (r.raw.strip() or r.model == "heuristic"):
                continue
            wrong.append((case["id"], expected, r.intent, r.reason, r.finish_reason))

    if wrong:
        lines = [
            f"{id}: expected {exp} got {got} ({reason}) finish={finish}"
            for id, exp, got, reason, finish in wrong
        ]
        pytest.fail("\n".join(lines))
