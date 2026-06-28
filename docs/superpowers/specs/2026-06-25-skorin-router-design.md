# Skorin Intent Router Design

**Date:** 2026-06-25 (updated 2026-06-25)  
**Status:** Implemented — live dispatch, legacy paths removed  
**Scope:** ProxyARC (Velocity)

## Goal

Single ingress pipeline for every global-chat message: micro-LLM classifies intent → handler runs the scenario. No parallel `BugTicketObserver` or direct `!` → assistant path.

## Architecture

```
ChatListener / DiscordListener
    └── ChatIngress
            └── MessagePipeline
                  1. ObserveStage       → ChatLog + assistant.observeChat()
                  2. RouteStage         → AssistantRouter (async)
                  3. IntentHandlerRegistry → IntentHandler per intent
```

### Intents

| Intent | Handler | Agent mode |
|--------|---------|------------|
| `skip` | `SkipIntentHandler` | — |
| `chat` | `ChatIntentHandler` | `AssistantRunMode.CHAT` (`chat.*` config) |
| `bug` | `BugIntentHandler` | `AssistantRunMode.BUG` (`bug.*` config) |

Router may still return legacy wire names `bug_new` / `bug_followup` — parsed as `bug`.

### Config (`assistant.yml`)

| Block | Purpose |
|-------|---------|
| `routing.*` | Router LLM, enabled intents, context limits |
| `chat.*` | Chat scenario, observe format, continuation window, display |
| `bug.*` | Bug scenario tools and enable flag |

`chat.observe-format` and `chat.continuation-window-sec` are shared by router observe lines and assistant meta (continuation after PM).

### Observe / context

- **ChatLog** — ring buffer for router `recent_chat`
- **assistant.observeChat()** — ambient chat layer for chat agent (trigger line not duplicated in history)
- **Bug** — ticket context injected via `extraHistoryLines` only when needed

### Adding a scenario

1. `RouteIntent` + `prompts/router.txt`
2. `IntentHandler` (or `AssistantIntentHandler`)
3. Register in `IntentHandlers.create()`
4. YAML block + `routing.enabled-intents`

## Key paths

| Area | Path |
|------|------|
| Ingress | `routing/ingress/ChatIngress.kt` |
| Pipeline | `routing/pipeline/MessagePipeline.kt` |
| Dispatch | `routing/dispatch/IntentHandlerRegistry.kt`, `handlers/*` |
| Router | `routing/router/*` |
| Wiring | `routing/RoutingModule.kt`, `ai/AssistantModule.kt` |

## Eval

Python offline eval: `tools/router_eval/` (may use legacy expected intents; production uses `bug`).
