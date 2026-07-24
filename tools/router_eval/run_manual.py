#!/usr/bin/env python3
"""Interactive manual router checks against live OpenRouter (production prompt + context)."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

from router import classify, load_router_prompt, router_input_from_dict

MANUAL_PATH = Path(__file__).with_name("manual_scenarios.json")
CASES_PATH = Path(__file__).with_name("cases.json")


def load_json(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def run_manual(
    scenarios: list[dict],
    model: str,
    delay_sec: float,
    pause: bool,
) -> int:
    if not os.environ.get("OPENROUTER_API_KEY"):
        print("ERROR: set OPENROUTER_API_KEY")
        return 2

    print(f"Model: {model}")
    print(f"Prompt (first 80 chars): {load_router_prompt()[:80]}…")
    print(f"Scenarios: {len(scenarios)}")
    print("=" * 72)

    issues = 0
    for i, scenario in enumerate(scenarios, start=1):
        sid = scenario.get("id", f"scenario_{i}")
        title = scenario.get("title", sid)
        print(f"\n[{i}/{len(scenarios)}] {sid}: {title}")
        print("-" * 72)

        if "steps_in_game" in scenario:
            print("Шаги в игре:")
            for step in scenario["steps_in_game"]:
                print(f"  • {step}")
        if "acceptance" in scenario:
            print("Ожидание:")
            for item in scenario["acceptance"]:
                print(f"  ✓ {item}")

        inp = router_input_from_dict(scenario["input"])
        expected = scenario.get("expected")

        try:
            result = classify(inp, model=model)
        except Exception as e:
            print(f"API ERROR: {e}")
            issues += 1
            if pause:
                input("Enter — следующий сценарий…")
            continue

        ok = expected is None or result.intent == expected
        mark = "OK" if ok else "MISMATCH"
        if not ok:
            issues += 1

        print(f"\nOpenRouter → {mark}")
        print(f"  intent={result.intent} conf={result.confidence:.2f} model={result.model}")
        print(f"  finish_reason={result.finish_reason}")
        print(f"  reason: {result.reason}")
        if expected:
            print(f"  expected: {expected}")
        if result.raw:
            print(f"  raw: {result.raw[:300]}")

        print("\nUser payload:")
        print(inp.to_user_content().replace("\n", "\n  "))

        if pause:
            input("\nEnter — следующий сценарий…")
        elif delay_sec > 0 and i < len(scenarios):
            time.sleep(delay_sec)

    print("\n" + "=" * 72)
    if issues:
        print(f"Завершено с {issues} проблем(ами) (API или mismatch)")
        return 1
    print("Все автоматические проверки intent совпали")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Manual Скорен router integration (OpenRouter)")
    parser.add_argument("--manual", type=Path, default=MANUAL_PATH, help="manual_scenarios.json")
    parser.add_argument("--cases", type=Path, default=CASES_PATH, help="cases.json (strict suite)")
    parser.add_argument("--model", default=os.environ.get("ROUTER_MODEL", "deepseek/deepseek-v4-flash"))
    parser.add_argument("--delay", type=float, default=1.0)
    parser.add_argument("--pause", action="store_true", help="Pause before each scenario")
    parser.add_argument("--suite", choices=["manual", "cases", "all"], default="manual")
    args = parser.parse_args()

    scenarios: list[dict] = []
    if args.suite in ("manual", "all"):
        scenarios.extend(load_json(args.manual))
    if args.suite in ("cases", "all"):
        scenarios.extend(load_json(args.cases))

    code = run_manual(scenarios, args.model, args.delay, args.pause)
    sys.exit(code)


if __name__ == "__main__":
    main()
