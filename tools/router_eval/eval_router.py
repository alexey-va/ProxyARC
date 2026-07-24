#!/usr/bin/env python3
"""Strict router eval against OpenRouter (production router.txt)."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

from router import classify, router_input_from_dict

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
    if not os.environ.get("OPENROUTER_API_KEY"):
        print("ERROR: OPENROUTER_API_KEY not set")
        return 2

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

        router_input = router_input_from_dict(raw_input)

        try:
            result = classify(router_input, model=model)
        except Exception as e:
            print(f"FAIL {rid}: API error — {e}")
            failed.append(rid)
            if delay_sec > 0:
                time.sleep(delay_sec)
            continue

        intent_ok = result.intent == expected
        acceptable = case.get("acceptable", [])
        if not intent_ok and acceptable and result.intent in acceptable:
            intent_ok = True
        has_response = result.raw.strip() != "" or result.model == "heuristic"
        ok = intent_ok and has_response
        mark = "OK"
        if ok and result.model == "heuristic":
            mark = "OK~"
        elif not ok:
            mark = "FAIL"
        if ok:
            passed += 1
        else:
            failed.append(rid)

        line = (
            f"{mark} {rid}: expected={expected} got={result.intent} "
            f"conf={result.confidence:.2f} finish={result.finish_reason}"
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


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate Скорен router on OpenRouter")
    parser.add_argument("--cases", type=Path, default=CASES_PATH)
    parser.add_argument(
        "--model",
        default=os.environ.get("ROUTER_MODEL", "deepseek/deepseek-v4-flash"),
    )
    parser.add_argument("--delay", type=float, default=1.0)
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    code = run_eval(args.cases, args.model, args.delay, args.verbose)
    sys.exit(code)


if __name__ == "__main__":
    main()
