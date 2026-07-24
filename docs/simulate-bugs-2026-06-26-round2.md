# Simulate — новые сценарии (round 2, 2026-06-26)

Deploy: ProxyARC JAR `b9a31212…` на Velocity.
Прогон: 5 **новых** сценариев последовательно через `POST /ops/skorin/simulate`.

## Сценарии

| # | Игрок | Сервер | Сообщение | Ожидание |
|---|--------|--------|-----------|----------|
| 1 | DriftWood | survival | `есть бага` | PM-вопрос, без тикета |
| 2 | DriftWood | survival | `/trade accept не открывает gui обмена` (reply) | create/update + PM |
| 3 | CopperFox | classic | `rtp не работает лол шучу бро` | skip, без тикета |
| 4 | SageWire | classic | `/ah sell съедает предмет но лот не появляется` | create + PM |
| 5 | SageWire | classic | `всё норм закрой тикет` (reply) | close RB + PM |

---

## Результаты

| # | Статус | LLM rounds | Tools | Заметки |
|---|--------|------------|-------|---------|
| 1 | **OK** | router + agent×1 | SendPrivateMessage | «есть бага — че конкретно…», survey opened |
| 2 | **FAIL** | router + agent×1 | **none** | `finish_reason=length`, `model_blank` — игрок без ответа и без тикета |
| 3 | **OK** | router only | — | skip, joke detected |
| 4 | **OK*** | router + agent×4 | List → Create RB-00012 → Update title → PM | лишние list+update; title нормализован на update |
| 5 | **OK** | router + policy + agent×1 | Update closed + PM | `skip→bug` override сработал |

\* — функционально ок (тикет + PM + закрытие), но дорого по latency.

---

## BUG-6 — follow-up survey: `finish_reason=length`, пустой ответ (critical)

**Сценарий:** #2 DriftWood после PM-only survey.

**Симптом:**
```
LLM ← … mode=bug_survey depth=0 … finish=length tools=[-] content=""
Assistant … skip … reason=model_blank
```

**Итог:** детали бага `/trade accept…` **не записаны**, PM не отправлен, тикет не создан.

**Гипотеза:** `max-tokens` / `completion=512` исчерпан на длинном tool-call JSON в bug_survey; модель не вернула tools.

**Fix (кандидаты):**
- поднять `max-tokens` для bug_survey в `assistant.yml`
- retry при `finish_reason=length` без tools
- hint/action: при detail + open survey → `createissueticket + sendprivatemessage` одним шагом

---

## BUG-3 (regression) — лишний `ListIssueTickets`

**Сценарий:** #4 SageWire (новый игрок, нет open ticket).

**Симптом:** depth=0 → ListIssueTickets → depth=1 → CreateIssueTicket.

**Fix:** tool-gating: не отдавать list при `ticket=new` и нет witness; или post-hint после пустого списка.

---

## BUG-7 — Create + немедленный Update на тот же репорт

**Сценарий:** #4 SageWire.

**Симптом:** CreateIssueTicket RB-00012 → сразу UpdateIssueTicket (title normalize + appendDescription дублирует триггер).

**Fix:** после успешного create не предлагать update в том же chain; нормализация title уже в CreateIssueTicket.

---

## OK / улучшения vs round 1

| Проверка | Round 2 |
|----------|---------|
| PM после create | #4 SageWire — PM на depth=3 ✓ |
| «закрой тикет» → bug agent | #5 — policy override ✓ |
| close + PM одним раундом | #5 — Update+PM parallel tools ✓ |
| Title `[Закрыт] … · Спавн` | #5 ✓ |
| Joke → skip | #3 CopperFox ✓ |
| Cross-player dialog в тикете | не проверялось (разные игроки, один RB) |

---

## Созданные тикеты (simulate)

- **RB-00012** — SageWire `/ah sell` (classic) — закрыт в #5
- DriftWood — **тикет не создан** (#2 fail)

---

## Latency (approx)

| # | Wall time |
|---|-----------|
| 1 | 12s |
| 2 | 14s (fail) |
| 3 | ~2s |
| 4 | ~27s (4 agent rounds) |
| 5 | 15s |
