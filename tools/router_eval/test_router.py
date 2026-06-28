"""pytest: offline parser tests + optional live OpenRouter eval."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from router import RouterInput, classify, parse_router_json

CASES_PATH = Path(__file__).with_name("cases.json")


class TestParseRouterJson:
    def test_plain_json(self) -> None:
        r = parse_router_json('{"intent":"chat","confidence":0.9,"reason":"скорен"}')
        assert r.intent == "chat"
        assert r.confidence == 0.9

    def test_fenced_json(self) -> None:
        raw = '```json\n{"intent":"skip","confidence":0.8,"reason":"флуд"}\n```'
        r = parse_router_json(raw)
        assert r.intent == "skip"

    def test_invalid_intent_fallback(self) -> None:
        r = parse_router_json('{"intent":"unknown","confidence":0.5,"reason":"x"}')
        assert r.intent == "skip"


class TestCasesFile:
    def test_cases_valid(self) -> None:
        with CASES_PATH.open(encoding="utf-8") as f:
            cases = json.load(f)
        assert len(cases) >= 10
        intents = {"skip", "chat", "bug_new", "bug_followup"}
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
    assert r.intent in {"skip", "chat", "bug_new", "bug_followup"}


@pytest.mark.live
def test_live_full_suite() -> None:
    if not os.environ.get("OPENROUTER_API_KEY"):
        pytest.skip("OPENROUTER_API_KEY not set")

    with CASES_PATH.open(encoding="utf-8") as f:
        cases = json.load(f)

    wrong = []
    for case in cases:
        inp = case["input"]
        expected = case["expected"]
        r = classify(
            RouterInput(
                player=inp["player"],
                message=inp["message"],
                server=inp.get("server"),
                flags=inp.get("flags"),
                open_ticket_id=inp.get("open_ticket_id"),
                seconds_since_bot=inp.get("seconds_since_bot"),
            ),
        )
        if r.intent != expected:
            wrong.append((case["id"], expected, r.intent, r.reason))

    if wrong:
        lines = [f"{id}: expected {exp} got {got} ({reason})" for id, exp, got, reason in wrong]
        pytest.fail("\n".join(lines))
