# Bug Survey Session Implementation Plan

**Goal:** Split bug info gathering into a dedicated survey agent with session store while the main router and chat agent keep running, with mutual awareness.

**Architecture:** `BugSurveySessionStore` + second `Assistant` (`bug-survey`); router context hints; `BugIntentHandler` opens session and dispatches survey assistant; chat prompt layer warns about active survey; `completebugs urvey` tool closes session; heuristic routes continuations to `bug` when survey active.

**Tech stack:** Kotlin, Velocity, existing `Assistant` / `MessagePipeline` / `DefaultTools`.

**Spec:** `docs/superpowers/specs/2026-06-28-bug-survey-session-design.md`

---

## File map

| File | Responsibility |
|------|----------------|
| `routing/survey/BugSurveySession.kt` | Session data class |
| `routing/survey/BugSurveySessionStore.kt` | Open/close/touch/timeout |
| `routing/context/RouterContext.kt` | Survey fields in `toUserContent()` |
| `routing/context/RouterContextBuilder.kt` | Populate survey from store |
| `routing/router/RouterHeuristicFallback.kt` | Survey continuation → `bug` |
| `routing/router/AssistantRouter.kt` | Pass survey flag into fallback |
| `routing/dispatch/handlers/BugIntentHandler.kt` | Open session, dispatch survey assistant |
| `routing/dispatch/assistant/BugSurveyAgentDispatch.kt` | Enqueue `bugSurveyAssistant` |
| `routing/dispatch/handlers/ChatIntentHandler.kt` | Unchanged path; hint via layers |
| `ai/AssistantPromptLayers.kt` | `bugSurveyActiveMessage` for chat |
| `ai/Assistant.kt` | Support `bug-survey` type section keys |
| `ai/AssistantModule.kt` | Init `Velocity.bugSurveyAssistant` |
| `velocity/Velocity.kt` | `bugSurveyAssistant` field |
| `tools/DefaultTools.kt` | `CompleteBugSurvey`, ticket hooks |
| `tools/Tools.kt` | Register tool |
| `resources/prompts/bug-ticket.txt` | Survey + complete tool + evaluate-before-create |
| `resources/prompts/router.txt` | `active_bug_survey` rules |
| `resources/modules/assistant.yml` | `bug.survey.*`, `bug-survey` section |
| Tests | Store, heuristic, context, tool |

---

### Task 1: Session store

**Files:** `BugSurveySession.kt`, `BugSurveySessionStore.kt`, `BugSurveySessionStoreTest.kt`

1. Create `BugSurveySession` data class.
2. Implement store: `open`, `isActive`, `get`, `touch`, `bindTicket`, `close`, `closeIdle(timeoutMs)`.
3. Write tests: open/close, bind ticket, timeout eviction.

**Verify:** `./gradlew test --tests '*BugSurveySession*'`

---

### Task 2: Router context

**Files:** `RouterContext.kt`, `RouterContextBuilder.kt`, `RouterContextBuilderTest.kt`

1. Add optional `activeBugSurvey: BugSurveySession?` to `RouterContext` (or inline fields).
2. Emit `active_bug_survey`, `survey_ticket`, `survey_topic` in `toUserContent()`.
3. Builder reads `BugSurveySessionStore.get(player)`.

**Verify:** `./gradlew test --tests '*RouterContext*'`

---

### Task 3: Router prompt + heuristic

**Files:** `router.txt`, `RouterHeuristicFallback.kt`, `RouterHeuristicFallbackTest.kt`, `AssistantRouter.kt`

1. Update router prompt for active survey routing rules.
2. In fallback: if survey active + continuation/reply_to_bot → `bug`; else keep chat heuristic.
3. Apply same rule when LLM returns empty (existing fallback path).

**Verify:** `./gradlew test --tests '*RouterHeuristic*'`

---

### Task 4: Second Assistant instance

**Files:** `Velocity.kt`, `AssistantModule.kt`, `assistant.yml`

1. Add `Velocity.bugSurveyAssistant`.
2. `AssistantModule.init`: create `Assistant(config, "bug-survey", llmClient, memoryStore with distinct key)`.
3. Reload/shutdown parity with `chatAssistant`.
4. Yaml: `bug-survey.enabled`, reuse `bug` prompt path or `bug-survey.prompt-file`.

**Verify:** Plugin loads without error (manual or existing integration smoke).

---

### Task 5: Bug survey dispatch

**Files:** `BugSurveyAgentDispatch.kt`, `BugIntentHandler.kt`, `IntentHandlers.kt`

1. `BugSurveyAgentDispatch.enqueue(context)` → `Velocity.bugSurveyAssistant`, mode BUG (or dedicated enum if needed).
2. `BugIntentHandler`: if `bug.survey.enabled`, `open`/`touch` session; use survey dispatch instead of shared `AssistantAgentDispatch` for BUG intent.
3. Keep dedup keys including session ticket id.

**Verify:** Manual log lines `Route bug` + survey session open.

---

### Task 6: `completebugs urvey` tool

**Files:** `DefaultTools.kt`, `Tools.kt`, `bug-ticket.txt`

1. Add `CompleteBugSurvey(note?)` → `BugSurveySessionStore.close(player, note)`.
2. Register in bug-survey tools list only.
3. Update prompt: when ticket sufficient, PM then complete tool; fluff → complete without create.

**Verify:** Unit test tool closes session.

---

### Task 7: Ticket tool hooks

**Files:** `DefaultTools.kt` (`CreateIssueTicket`, `UpdateIssueTicket`)

1. On create success: `bindTicket(reporter, id, title)`.
2. On update `status=closed`: `close(reporter, "ticket_closed")`.
3. `SendPrivateMessage`: observe line on `chatAssistant` (already); ensure `recordBotReply` for survey PMs.

**Verify:** Session binds ticket id after create.

---

### Task 8: Chat awareness layer

**Files:** `AssistantPromptLayers.kt`, `Assistant.kt`, `chat.txt` (optional one-liner)

1. `bugSurveyActiveContextMessage(player)` when store active.
2. Inject in chat `tryEnqueue` path only (not bug-survey).
3. Short chat prompt note: respect active survey.

**Verify:** Unit test layer present when session active.

---

### Task 9: Timeout sweep

**Files:** `BugSurveySessionStore.kt`, `RoutingModule.kt` or `AssistantModule.kt`

1. On each ingress or scheduled tick: `closeIdle(10 * 60 * 1000)`.
2. Config `bug.survey.timeout-minutes` default 10.

**Verify:** Test idle close.

---

### Task 10: Deploy assets

**Files:** `mcserver/velocity/plugins/proxyarc/modules/assistant.yml` (if mirrored), bundled prompts.

1. Copy updated `router.txt`, `bug-ticket.txt`, yaml defaults.
2. Build jar: `./scripts/mc proxyarc --fast` or gradle per project convention.

**Verify:** Deploy to velocity; smoke: bug report → PM → detail → complete.

---

## Out of scope

- Router eval case changes (survey is post-router; eval unchanged).
- Redis persistence of survey session (in-memory OK for v1).
- Discord inbound special casing beyond existing pipeline.
