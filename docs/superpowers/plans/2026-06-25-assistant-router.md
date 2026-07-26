# Скорен Router — implementation plan

**Status:** Done (live dispatch). Shadow phase and legacy paths removed.

See current architecture: `docs/superpowers/specs/2026-06-25-assistant-router-design.md`

## Completed

- `ChatIngress` → `MessagePipeline` (Observe → Route → `IntentHandlerRegistry`)
- Intents: `skip`, `chat`, `bug`
- Separate config: `routing`, `chat`, `bug` in `assistant.yml`
- Handlers: `SkipIntentHandler`, `ChatIntentHandler`, `BugIntentHandler`
- Prompts: `router.txt`, `chat.txt`, `bug-ticket.txt`

## Optional follow-ups

- Port `tools/router_eval/cases.json` to expect `bug` instead of `bug_new`/`bug_followup`
- PM reply listener for bug follow-up outside global chat
- Router rule: bug reports with `!скорен` → always `bug` intent
