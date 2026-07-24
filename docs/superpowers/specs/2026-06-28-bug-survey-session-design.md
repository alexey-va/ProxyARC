# Bug Survey Session — Design Spec

**Date:** 2026-06-28
**Project:** ProxyARC (Velocity)
**Status:** Approved

## Goal

Separate **bug info gathering** from **public chat** while keeping the main router alive. When a player is in an active bug-survey session, the router still classifies every message; bug details go to a dedicated survey agent, social chat goes to the chat agent. Both sides know the survey is active so they do not duplicate tickets or PMs.

## Locked decisions

| Topic | Choice |
|-------|--------|
| Router during survey | Always runs (`skip` / `chat` / `bug`) |
| Survey message scope | Only messages routed to `bug` (not all player messages) |
| Agent separation | Two `Assistant` instances: `chat` + `bug-survey` |
| Survey awareness | Router context + chat trigger hint when survey active |
| Heuristic fallback | When `active_bug_survey` + `continuation_with_bot` or `reply_to_bot` → `bug` (if LLM empty/failed) |
| Session open | Router routes `bug` on new breakage report |
| Session close | `completebugs urvey` tool, ticket `status=closed`, or **10 min** idle timeout |
| Not auto-close on create | Agent may PM / update without closing; evaluates content first |
| Ticket creation | Agent decides: joke/fluff → no ticket + complete; unclear → PM only; enough info → create |

## Architecture

```
ChatIngress / DiscordIngress
    └── MessagePipeline
          ObserveStage (observe buffer → chatAssistant only)
          RouteStage (LLM router + context including active_bug_survey)
          IntentHandlerRegistry
              skip → SkipIntentHandler
              chat → ChatIntentHandler → chatAssistant (with survey hint)
              bug  → BugIntentHandler → bugSurveyAssistant + session store
```

### Router context (new fields)

When `BugSurveySessionStore.isActive(player)`:

```
active_bug_survey=true
survey_ticket=RB-00042|null
survey_topic=rtp не работает|null
```

Router prompt: with active survey, bug details → `bug`; «ку скорен» / voice / small talk → `chat` or `skip`; open_ticket alone does not force `bug`.

### Chat agent hint

Injected via `AssistantPromptLayers` for `CHAT` mode when survey active:

```
активный bug-survey: {topic} ({ticketId}) — не создавай тикеты, не опрашивай в личку по этому багу; survey-процесс сам разберётся
```

Chat agent has no bug ticket tools in normal config.

### Bug survey agent

- `Assistant` with `type = "bug-survey"` (or `bug`), own history and memory key.
- Tools: `createissueticket`, `updateissueticket`, `sendprivatemessage`, `listissuetickets`, `completebugs urvey`.
- Never public chat reply.
- Prompt: evaluate message before create; scenarios for new report, continuation, close, complete.

### `completebugs urvey` tool

- Closes `BugSurveySessionStore` for `lastTriggerPlayer`.
- Optional `note` for logs.
- Not sent to player.

### Session store

`BugSurveySessionStore` (in-memory, per Velocity instance):

```kotlin
data class BugSurveySession(
    val player: String,
    val startedAtMs: Long,
    var lastActivityAtMs: Long,
    var ticketId: String? = null,
    var topicHint: String? = null,
)
```

- `open(player)` on first `bug` dispatch when no active session.
- `touch(player)` on each survey enqueue.
- `bindTicket(player, ticketId, topic)` on successful `createissueticket`.
- `close(player, reason)` on complete tool, closed ticket, timeout sweep.
- Background or per-message check: idle > `bug.survey.timeout-minutes` (default **10**) → close.

### Heuristic fallback (survey only)

Extend `RouterHeuristicFallback` and/or post-LLM policy:

If `active_bug_survey` and (`meta.replyToBot` or `meta.continuationWithBot`) and LLM failed/empty → `bug` (not `chat`).

Existing continuation → `chat` heuristic applies only when survey **not** active.

### Observe buffer

`ObserveStage` continues using `chatAssistant.observeChat()` so chat agent and router see PM lines. `sendprivatemessage` from survey agent should also record observe lines (via `RoutingModule.recordBotReply` + observe on chat assistant buffer).

### Ticket tool hooks

- `CreateIssueTicket`: after create, `BugSurveySessionStore.bindTicket(reporter, id, title)`.
- `UpdateIssueTicket`: if `status=closed`, `BugSurveySessionStore.close(reporter, "ticket_closed")`.

## Config (`assistant.yml`)

```yaml
bug:
  enabled: true
  survey:
    enabled: true
    timeout-minutes: 10
```

Separate model/max-tokens for `bug-survey` section if needed (reuse `bug.*` keys).

## What does not change

- Router has no auto-bug bias from `open_ticket` alone.
- `InboundMessage.allowsChatRouting()` (`!` prefix rule) unchanged.
- Eval cases: router still does not route PM follow-ups to `bug` without breakage signal.

## Success criteria

1. New bug report → survey opens → PM/questions/ticket without chat agent duplicating.
2. During survey, `!скорен го в войс` → chat handler, not survey.
3. During survey, `на survival` after PM → router/heuristic → `bug` → survey updates ticket.
4. Survey completes → `completebugs urvey` or timeout → router normal; chat hint removed.
5. Joke routed to `bug` once → agent completes without ticket.

## Testing

- Unit: `BugSurveySessionStore`, heuristic with/without active session, `CompleteBugSurvey` tool.
- Unit: `RouterContext.toUserContent()` includes survey fields.
- Existing router tests updated for survey heuristic branch.
