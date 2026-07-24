"""Роутер Скорена — same user payload as Kotlin RouterContext.toUserContent()."""

from __future__ import annotations

import json
import os
import re
import ssl
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal

try:
    import certifi

    SSL_CONTEXT = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    SSL_CONTEXT = None

Intent = Literal["skip", "chat", "bug"]

VALID_INTENTS: frozenset[str] = frozenset({"skip", "chat", "bug"})

DEFAULT_MODEL = "deepseek/deepseek-v4-flash"
DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

PROMPTS_DIR = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "prompts"


def load_router_prompt() -> str:
    path = PROMPTS_DIR / "router.txt"
    if path.is_file():
        return path.read_text(encoding="utf-8").strip()
    return (
        "Ты — роутер Скорена. Ответь JSON: "
        '{"intent":"skip|chat|bug","confidence":0.0-1.0,"reason":"..."}'
    )


@dataclass
class RouterInput:
    player: str
    message: str
    raw_text: str | None = None
    server: str | None = None
    source: str = "game"
    directed_at_bot: bool = False
    reply_to_bot: bool = False
    continuation_with_bot: bool = False
    seconds_since_bot: int | None = None
    reply_to_player: str | None = None
    chat_allowed: bool | None = None
    open_ticket_id: str | None = None
    open_ticket_status: str = "open"
    open_ticket_summary: str | None = None
    open_ticket_server: str | None = None
    recent_chat: list[str] = field(default_factory=list)
    recent_open_tickets: list[str] = field(default_factory=list)
    recent_routes: list[str] = field(default_factory=list)
    active_bug_survey: bool = False
    survey_ticket: str | None = None
    survey_topic: str | None = None

    def resolved_chat_allowed(self) -> bool:
        if self.chat_allowed is not None:
            return self.chat_allowed
        if self.source == "discord":
            return True
        raw = self.raw_text if self.raw_text is not None else self.message
        return raw.startswith("!")

    def to_user_content(self) -> str:
        parts: list[str] = [
            f"player={self.player}",
        ]
        if self.server:
            parts.append(f"server={self.server}")
        parts.append(f"message={self.message}")
        parts.append(f"directed_at_bot={self.directed_at_bot}")
        parts.append(f"reply_to_bot={self.reply_to_bot}")
        parts.append(f"continuation_with_bot={self.continuation_with_bot}")
        parts.append(
            f"seconds_since_bot={self.seconds_since_bot if self.seconds_since_bot is not None else 'null'}",
        )
        parts.append(
            f"reply_to_player={self.reply_to_player if self.reply_to_player else 'null'}",
        )
        parts.append(f"source={self.source}")
        parts.append(f"chat_allowed={self.resolved_chat_allowed()}")
        parts.append("")
        if self.active_bug_survey:
            parts.append("active_bug_survey=true")
            if self.survey_ticket:
                parts.append(f"survey_ticket={self.survey_ticket}")
            if self.survey_topic:
                parts.append(f"survey_topic={self.survey_topic}")
            parts.append("")

        if self.recent_chat:
            parts.append("recent_chat:")
            parts.extend(self.recent_chat)
            parts.append("")

        if self.open_ticket_id:
            parts.append("open_ticket:")
            parts.append(f"id={self.open_ticket_id}")
            parts.append(f"status={self.open_ticket_status}")
            if self.open_ticket_summary:
                parts.append(f"summary={self.open_ticket_summary}")
            if self.open_ticket_server:
                parts.append(f"server={self.open_ticket_server}")
            parts.append("")

        if self.recent_open_tickets:
            parts.append("recent_open_tickets:")
            parts.extend(self.recent_open_tickets)
            parts.append("")

        if self.recent_routes:
            parts.append("recent_routes:")
            for line in self.recent_routes:
                parts.append(f"- {line}")

        return "\n".join(parts).strip()


@dataclass(frozen=True)
class RouterResult:
    intent: Intent
    confidence: float
    reason: str
    raw: str
    model: str | None = None
    finish_reason: str | None = None

    @property
    def ok(self) -> bool:
        return self.intent in VALID_INTENTS and self.raw.strip() != ""


def parse_router_json(raw: str) -> RouterResult:
    text = raw.strip()
    if not text:
        raise ValueError("empty response")

    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)

    try:
        data: dict[str, Any] = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r'"intent"\s*:\s*"([^"]+)"', text, re.IGNORECASE)
        if not match:
            raise
        intent_raw = match.group(1).strip().lower()
        intent = intent_raw if intent_raw in VALID_INTENTS else "skip"
        if intent_raw in {"bug_new", "bug_followup"}:
            intent = "bug"
        return RouterResult(
            intent=intent,
            confidence=0.5,
            reason="partial_json",
            raw=raw,
        )

    intent_raw = str(data.get("intent", "skip")).strip().lower()
    if intent_raw in {"bug_new", "bug_followup"}:
        intent: Intent = "bug"
    elif intent_raw in VALID_INTENTS:
        intent = intent_raw
    else:
        intent = "skip"

    confidence = float(data.get("confidence", 0.5))
    confidence = max(0.0, min(1.0, confidence))
    reason = str(data.get("reason", "")).strip()
    return RouterResult(intent=intent, confidence=confidence, reason=reason, raw=raw)


def apply_heuristic_fallback(input: RouterInput, failed: RouterResult) -> RouterResult:
    if input.active_bug_survey and (input.reply_to_bot or input.continuation_with_bot):
        return _heuristic_result("bug", "heuristic:survey_continuation", failed)
    if input.reply_to_bot or input.continuation_with_bot:
        return _heuristic_result("chat", "heuristic:continuation_with_bot", failed)
    raw = input.raw_text if input.raw_text is not None else input.message
    if input.directed_at_bot and raw.startswith("!"):
        return _heuristic_result("chat", "heuristic:directed_at_bot", failed)
    return failed


def _heuristic_result(intent: Intent, reason: str, failed: RouterResult) -> RouterResult:
    return RouterResult(
        intent=intent,
        confidence=0.55,
        reason=f"{reason}; llm={failed.reason}",
        raw=json.dumps({"intent": intent, "confidence": 0.55, "reason": reason}),
        model="heuristic",
        finish_reason=failed.finish_reason,
    )


def _openrouter_complete(
    input: RouterInput,
    *,
    api_key: str,
    model: str,
    prompt: str,
    base_url: str,
    timeout_sec: float,
    max_tokens: int,
) -> RouterResult:
    body = {
        "model": model,
        "temperature": 0,
        "max_tokens": max_tokens,
        "messages": [
            {"role": "system", "content": prompt},
            {"role": "user", "content": input.to_user_content()},
        ],
    }

    req = urllib.request.Request(
        f"{base_url.rstrip('/')}/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://rus-crafting.ru",
            "X-Title": "Скорен Router Eval",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(
            req,
            timeout=timeout_sec,
            context=SSL_CONTEXT,
        ) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"OpenRouter HTTP {e.code}: {detail}") from e

    choice = payload.get("choices", [{}])[0]
    message = choice.get("message", {})
    content = message.get("content", "") or ""
    used_model = payload.get("model")
    finish_reason = choice.get("finish_reason")

    if not content.strip():
        return RouterResult(
            intent="skip",
            confidence=0.0,
            reason="empty response",
            raw=content,
            model=used_model,
            finish_reason=finish_reason,
        )

    try:
        result = parse_router_json(content)
    except (json.JSONDecodeError, TypeError, ValueError):
        return RouterResult(
            intent="skip",
            confidence=0.0,
            reason="parse_failed",
            raw=content,
            model=used_model,
            finish_reason=finish_reason,
        )

    return RouterResult(
        intent=result.intent,
        confidence=result.confidence,
        reason=result.reason,
        raw=content,
        model=used_model,
        finish_reason=finish_reason,
    )


def classify(
    input: RouterInput,
    *,
    api_key: str | None = None,
    model: str = DEFAULT_MODEL,
    base_url: str = DEFAULT_BASE_URL,
    timeout_sec: float = 60.0,
    max_tokens: int = 256,
    system_prompt: str | None = None,
) -> RouterResult:
    key = api_key or os.environ.get("OPENROUTER_API_KEY")
    if not key:
        raise RuntimeError("OPENROUTER_API_KEY is not set")

    prompt = system_prompt or load_router_prompt()
    first = _openrouter_complete(
        input,
        api_key=key,
        model=model,
        prompt=prompt,
        base_url=base_url,
        timeout_sec=timeout_sec,
        max_tokens=max_tokens,
    )
    if first.raw.strip() and first.reason not in {"empty response", "parse_failed"}:
        return first

    second = _openrouter_complete(
        input,
        api_key=key,
        model=model,
        prompt=prompt,
        base_url=base_url,
        timeout_sec=timeout_sec,
        max_tokens=max_tokens,
    )
    if second.raw.strip() and second.reason not in {"empty response", "parse_failed"}:
        return second

    return apply_heuristic_fallback(input, second)


def router_input_from_dict(data: dict[str, Any]) -> RouterInput:
    return RouterInput(
        player=data["player"],
        message=data["message"],
        raw_text=data.get("raw_text"),
        server=data.get("server"),
        source=data.get("source", "game"),
        directed_at_bot=bool(data.get("directed_at_bot", False)),
        reply_to_bot=bool(data.get("reply_to_bot", False)),
        continuation_with_bot=bool(data.get("continuation_with_bot", False)),
        seconds_since_bot=data.get("seconds_since_bot"),
        reply_to_player=data.get("reply_to_player"),
        chat_allowed=data.get("chat_allowed"),
        open_ticket_id=data.get("open_ticket_id"),
        open_ticket_status=data.get("open_ticket_status", "open"),
        open_ticket_summary=data.get("open_ticket_summary"),
        open_ticket_server=data.get("open_ticket_server"),
        recent_chat=list(data.get("recent_chat", [])),
        recent_open_tickets=list(data.get("recent_open_tickets", [])),
        recent_routes=list(data.get("recent_routes", [])),
        active_bug_survey=bool(data.get("active_bug_survey", False)),
        survey_ticket=data.get("survey_ticket"),
        survey_topic=data.get("survey_topic"),
    )
