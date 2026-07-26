# Simulate bugs — complex scenarios (2026-06-26)

Прогон: 5 сценариев **по очереди** через `POST /ops/assistant/simulate` на Velocity после deploy `08a303a9`.

## Сценарии

| # | Игрок | Сообщение | Ожидание |
|---|--------|-----------|----------|
| 1 | EchoBolt | `!есть бага` | PM-вопрос, без тикета |
| 2 | EchoBolt | `!/warp shop крашит клиент на classic` (reply) | update/create + PM |
| 3 | IronPaw | `!скорен это баг что rtp … лол` | не заводить тикет / complete survey |
| 4 | MoonLint | `!скорен в gui кланов … null null` | create + PM |
| 5 | MoonLint | `!ладно починилось закрой тикет` (reply) | update closed + PM |

---

## BUG-1 — `createissueticket` без обязательного PM (critical)

**Сценарии:** #2 EchoBolt, #4 MoonLint (и ранее NetherDiver, MoonLint GUI).

**Симптом:** chain завершается с `ticket handled, chain complete` без `SendPrivateMessage`.

**Причина:** `shouldFinishBugChain()` возвращает `true` при `ticketHandled`, PM не требуется.

**Fix:** не завершать chain, пока `ticketHandled && !privateMessageSent`.

---

## BUG-2 — «закрой тикет» уходит в chat, тикет не закрывается (critical)

**Сценарий:** #5 MoonLint.

**Симптом:** router → `chat`, публичный ответ «RB закрыл» без `updateissueticket`.

**Причина:**
- survey не открыт (нет PM после create в #4);
- `applyActiveSurveyOverride` не срабатывает;
- LLM router выбирает chat для «починилось закрой тикет».

**Fix:** override router → `bug`, если у игрока open ticket и `looksLikeResolved` / «закрой тикет».

---

## BUG-3 — лишний `ListIssueTickets` (+1 LLM round)

**Сценарии:** #2, #4 (и большинство create-flow).

**Симптом:** depth=0 → ListIssueTickets → depth=1 → CreateIssueTicket.

**Причина:** промпт «list before create»; модель слушается даже для нового игрока без тикетов.

**Fix:** hint «skip list on fresh report»; tool-result подсказка при пустом списке.

---

## BUG-4 — survey follow-up создаёт новый тикет вместо append

**Сценарий:** #2 EchoBolt после vague PM.

**Симптом:** `CreateIssueTicket` RB-00009 вместо `updateissueticket` к существующему survey.

**Причина:** до create не было ticketId в session; модель не связала follow-up с PM-only survey.

**Fix:** после PM-only survey hint на detail → `createissueticket + PM` один раз; при bound ticket → только update.

---

## BUG-5 — router: joke+«баг» → bug (minor)

**Сценарий:** #3 IronPaw (`…лол`).

**Симптом:** router `bug` 0.95; agent справился PM «не баг».

**Fix (optional):** heuristic: «лол» + rtp без UI → chat/skip или force completebugs urvey in hint.

---

## OK

| # | Результат |
|---|-----------|
| 1 | PM only ✓ |
| 3 | Agent PM «не баг» ✓ (router лишний) |

---

## Status

| ID | Fix | Status |
|----|-----|--------|
| BUG-1 | `shouldFinishBugChain` — PM обязателен после ticket ops | **fixed** |
| BUG-2 | `applyOpenTicketOverride` + `looksLikeCloseTicket` | **fixed** |
| BUG-3 | hint «do not listissuetickets» | **partial** (prompt/hint) |
| BUG-4 | survey hint create when ticketId null + detail | **fixed** |
| BUG-5 | optional joke heuristic | deferred |

---

## Verify after fix (deploy `0b627e74`)

| Check | Result |
|-------|--------|
| Quartz77 create + PM | depth=0 list → depth=1 create → depth=2 **PM** ✓ (BUG-1) |
| MoonLint «закрой тикет» | router chat→**bug** override ✓; `UpdateIssueTicket status=closed title=[Закрыт]…` ✓ (BUG-2) |
| MoonLint close PM | PM после close **нет** — chain `survey closed` (minor, отдельный тикет) |
| ListIssueTickets | Quartz77 всё ещё лишний round (BUG-3 partial) |
