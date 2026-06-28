"""Skorin intent router — micro-LLM via OpenRouter (openrouter/free)."""

from __future__ import annotations

import json
import os
import re
import ssl
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Literal

try:
    import certifi

    SSL_CONTEXT = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    SSL_CONTEXT = None

Intent = Literal["skip", "chat", "bug_new", "bug_followup"]

VALID_INTENTS: frozenset[str] = frozenset({"skip", "chat", "bug_new", "bug_followup"})

DEFAULT_MODEL = "openrouter/free"
DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

ROUTER_SYSTEM_PROMPT = """
Ты — роутер для бота «Скорен» на Minecraft-сервере. По одной реплике игрока выбери intent.

intents:
- skip — обычный чат, флуд, разговор не с ботом, шутки, не баг
- chat — обращение к Скорену (скорен, бот, @addscoren) или явное продолжение диалога с ним
- bug_new — реальный репорт бага/поломки сервера (краш, не работает, дюп, лаги как поломка)
- bug_followup — дополнение к уже открытому тикету (есть open_ticket_id)

правила:
- шутки про баг («как баг», «это фича») → skip
- баг без обращения к скорену → bug_new (не chat)
- если open_ticket_id задан и игрок дописывает детали → bug_followup
- если сказали скорен и это не баг → chat
- если сказали скорен и репорт бага → chat (бот может ответить в чат; тикет опционально)

ответь ОДНИМ JSON объектом без markdown:
{"intent":"skip|chat|bug_new|bug_followup","confidence":0.0-1.0,"reason":"коротко"}
""".strip()


@dataclass(frozen=True)
class RouterInput:
    player: str
    message: str
    server: str | None = None
    flags: str | None = None
    open_ticket_id: str | None = None
    seconds_since_bot: int | None = None

    def to_user_content(self) -> str:
        parts = [
            f"player={self.player}",
            f"message={self.message}",
        ]
        if self.server:
            parts.append(f"server={self.server}")
        if self.flags:
            parts.append(f"flags={self.flags}")
        if self.open_ticket_id:
            parts.append(f"open_ticket_id={self.open_ticket_id}")
        if self.seconds_since_bot is not None:
            parts.append(f"seconds_since_bot={self.seconds_since_bot}")
        return "\n".join(parts)


@dataclass(frozen=True)
class RouterResult:
    intent: Intent
    confidence: float
    reason: str
    raw: str
    model: str | None = None

    @property
    def ok(self) -> bool:
        return self.intent in VALID_INTENTS


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
        match = re.search(r"\{[^{}]*\"intent\"[^{}]*\}", text, re.DOTALL)
        if not match:
            raise
        data = json.loads(match.group(0))

    intent = str(data.get("intent", "skip")).strip().lower()
    if intent not in VALID_INTENTS:
        intent = "skip"
    confidence = float(data.get("confidence", 0.5))
    confidence = max(0.0, min(1.0, confidence))
    reason = str(data.get("reason", "")).strip()
    return RouterResult(intent=intent, confidence=confidence, reason=reason, raw=raw)


def classify(
    input: RouterInput,
    *,
    api_key: str | None = None,
    model: str = DEFAULT_MODEL,
    base_url: str = DEFAULT_BASE_URL,
    timeout_sec: float = 60.0,
) -> RouterResult:
    key = api_key or os.environ.get("OPENROUTER_API_KEY")
    if not key:
        raise RuntimeError("OPENROUTER_API_KEY is not set")

    body = {
        "model": model,
        "temperature": 0,
        "max_tokens": 200,
        "messages": [
            {"role": "system", "content": ROUTER_SYSTEM_PROMPT},
            {"role": "user", "content": input.to_user_content()},
        ],
    }

    req = urllib.request.Request(
        f"{base_url.rstrip('/')}/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://rus-crafting.ru",
            "X-Title": "Skorin Router Eval",
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

    try:
        result = parse_router_json(content)
    except (json.JSONDecodeError, TypeError, ValueError):
        return RouterResult(
            intent="skip",
            confidence=0.0,
            reason="parse_failed",
            raw=content,
            model=used_model,
        )

    return RouterResult(
        intent=result.intent,
        confidence=result.confidence,
        reason=result.reason,
        raw=content,
        model=used_model,
    )
