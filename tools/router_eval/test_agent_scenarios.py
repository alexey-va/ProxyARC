#!/usr/bin/env python3
"""Offline checks for bug-agent UX: action hints + optional live simulate on Velocity."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

SCENARIOS = Path(__file__).with_name("agent_scenarios.json")

# Mirror BugSurveyActionHint.kt (keep in sync for offline hint tests)
UI_PATTERNS = ("меню", "написано", "текст", "надпис", "gui", "интерфейс")
VAGUE = (
    "есть бага",
    "есть баг",
    "баг",
    "не работает",
    "не пашет",
    "не паш",
    "сломал",
    "че то не так",
    "что-то не так",
)
JOKE = ("как баг", "это фича", "не баг", "шучу", "анекдот", "го в войс")
BUG = (
    "баг",
    "bug",
    "не работает",
    "не пашет",
    "rtp",
    "ртп",
    "/rtp",
    "меню",
    "написано",
    "сломал",
)


def looks_like_joke(text: str) -> bool:
    lower = text.lower()
    return any(p in lower for p in JOKE)


def looks_like_bug(text: str) -> bool:
    lower = text.lower().strip()
    if len(lower) < 3 or looks_like_joke(lower):
        return False
    return any(p in lower for p in BUG)


def is_vague(lower: str) -> bool:
    if len(lower) > 48:
        return False
    if "/" in lower or "survival" in lower or "classic" in lower or "биом" in lower:
        return False
    return any(p in lower for p in VAGUE)


def turn_hint(message: str, ticket_id: str | None) -> str | None:
    text = message.strip()
    if not text or not ticket_id:
        return None
    lower = text.lower()
    ticket = ticket_id
    if any(p in lower for p in UI_PATTERNS):
        action = f"updateissueticket ticketId={ticket} ... sendprivatemessage"
    elif is_vague(lower):
        action = "sendprivatemessage"
    elif looks_like_bug(text) and not looks_like_joke(text):
        action = f"updateissueticket ticketId={ticket} ... sendprivatemessage"
    elif looks_like_joke(text) and not looks_like_bug(text):
        action = "completebugs urvey"
    else:
        action = f"sendprivatemessage updateissueticket {ticket}"
    return action


def test_hint_scenarios() -> None:
    data = json.loads(SCENARIOS.read_text(encoding="utf-8"))
    failed = 0
    for row in data:
        hint = turn_hint(row["message"], row.get("ticket_id"))
        if hint is None:
            print(f"FAIL {row['id']}: no hint")
            failed += 1
            continue
        for needle in row.get("expect_hint_contains", []):
            if needle.lower() not in hint.lower():
                print(f"FAIL {row['id']}: hint missing {needle!r} -> {hint!r}")
                failed += 1
                break
        else:
            print(f"OK   {row['id']}: {row['note']}")
    if failed:
        sys.exit(1)


LIVE_STEPS = [
    ("vague", "есть бага", False),
    ("rtp_detail", "rtp не работает в мире биомов", True),
    ("menu_ui", "в меню написано скорен лох", True),
    ("resolved", "123", True),
]


def live_simulate(host: str, token: str, player: str = "SimTestPlayer") -> None:
    import urllib.error
    import urllib.request

    print(f"\n=== Live simulate on {host} player={player} ===")
    results = []
    for step_id, message, reply in LIVE_STEPS:
        body = json.dumps(
            {"player": player, "message": message, "reply_to_bot": reply},
            ensure_ascii=False,
        ).encode()
        req = urllib.request.Request(
            f"http://127.0.0.1:25825/ops/skorin/simulate",
            data=body,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        t0 = time.time()
        try:
            proc = subprocess.run(
                ["ssh", host, f"curl -sS -m 90 -H 'Authorization: Bearer {token}' -H 'Content-Type: application/json' -d @-", "http://127.0.0.1:25825/ops/skorin/simulate"],
                input=body,
                capture_output=True,
                timeout=100,
            )
            raw = proc.stdout.decode()
            dt = time.time() - t0
            payload = json.loads(raw)
            ok = payload.get("ok") and payload.get("agentWait", "").startswith("completed")
            status = "OK" if ok else "FAIL"
            print(f"{status} {step_id} ({dt:.1f}s) intent={payload.get('intent')} wait={payload.get('agentWait')}")
            results.append((step_id, ok, dt, payload))
        except Exception as e:
            print(f"FAIL {step_id}: {e}")
            results.append((step_id, False, 0, {}))
    failed = sum(1 for _, ok, _, _ in results if not ok)
    print(f"\nLive: {len(results) - failed}/{len(results)} passed")
    if failed:
        sys.exit(1)


def main() -> None:
    test_hint_scenarios()
    if os.environ.get("RUN_LIVE_AGENT_SIM") == "1":
        host = os.environ.get("MC_VELOCITY_HOST", "velocity")
        token = os.environ.get("PROXYARC_OPS_TOKEN", "")
        if not token:
            print("SKIP live: set PROXYARC_OPS_TOKEN")
            return
        live_simulate(host, token)


if __name__ == "__main__":
    main()
