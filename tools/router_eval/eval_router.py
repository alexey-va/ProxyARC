#!/usr/bin/env python3
"""Run router eval against OpenRouter openrouter/free."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from router import RouterInput, classify, parse_router_json

CASES_PATH = Path(__file__).with_name("cases.json")


def load_cases(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def run_eval(
    cases_path: Path,
    model: str,
    delay_sec: float,
    verbose: bool,
) -> int:
    cases = load_cases(cases_path)
    passed = 0
    failed: list[str] = []

    print(f"Model: {model}")
    print(f"Cases: {len(cases)}")
    print("-" * 72)

    for i, case in enumerate(cases, start=1):
        raw_input = case["input"]
        expected = case["expected"]
        rid = case.get("id", f"case_{i}")

        router_input = RouterInput(
            player=raw_input["player"],
            message=raw_input["message"],
            server=raw_input.get("server"),
            flags=raw_input.get("flags"),
            open_ticket_id=raw_input.get("open_ticket_id"),
            seconds_since_bot=raw_input.get("seconds_since_bot"),
        )

        try:
            result = classify(router_input, model=model)
        except Exception as e:
            print(f"FAIL {rid}: API error — {e}")
            failed.append(rid)
            if delay_sec > 0:
                time.sleep(delay_sec)
            continue

        ok = result.intent == expected
        mark = "OK" if ok else "FAIL"
        if ok:
            passed += 1
        else:
            failed.append(rid)

        line = (
            f"{mark} {rid}: expected={expected} got={result.intent} "
            f"conf={result.confidence:.2f} model={result.model or '?'}"
        )
        print(line)
        if verbose or not ok:
            print(f"    msg: {router_input.message}")
            print(f"    reason: {result.reason}")
            if not ok:
                print(f"    raw: {result.raw[:200]}")

        if delay_sec > 0 and i < len(cases):
            time.sleep(delay_sec)

    print("-" * 72)
    pct = (passed / len(cases) * 100) if cases else 0.0
    print(f"Passed: {passed}/{len(cases)} ({pct:.1f}%)")
    if failed:
        print(f"Failed: {', '.join(failed)}")
        return 1
    return 0


def test_parse_router_json() -> None:
    raw = '{"intent":"bug_new","confidence":0.91,"reason":"rtp broken"}'
    r = parse_router_json(raw)
    assert r.intent == "bug_new"
    assert r.confidence == 0.91


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate Skorin router on OpenRouter")
    parser.add_argument("--cases", type=Path, default=CASES_PATH, help="JSON test cases")
    parser.add_argument("--model", default="openrouter/free", help="OpenRouter model slug")
    parser.add_argument("--delay", type=float, default=1.0, help="Delay between API calls (rate limits)")
    parser.add_argument("--self-test", action="store_true", help="Run offline parse self-test only")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        test_parse_router_json()
        print("self-test OK")
        return

    code = run_eval(args.cases, args.model, args.delay, args.verbose)
    sys.exit(code)


if __name__ == "__main__":
    main()
