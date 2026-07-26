# Simulate — chaos multi-player (2026-06-29)

Deploy: ProxyARC на Velocity (после title-dedup + world names).
Скрипт: `tools/run_chaos_simulate.sh` → `POST /ops/assistant/simulate`.
Прогон: **35 шагов**, ~**9 мин** wall time. Лог: `/tmp/chaos-sim-1782765635.log`, JSONL: `/tmp/chaos-sim-results.jsonl`.

Параметры: `SIM_WAIT_BUG=75`, `SIM_WAIT_SKIP=20`, фоновый шум между баг-репортами.

---

## Сценарии (5 блоков)

| Блок | Тема | Игроки | Шаги |
|------|------|--------|------|
| **A** | Аукцион NaN + свидетели | NovaShard, KiteRun + шум | vague → detail → witness → close |
| **B** | /grave + /kit vip dup | IronVeil, Flint909 + шум | detail → follow-up → witness → close |
| **C** | Шутка → реальный баг | CopperFox + шум | joke skip → vague → detail → self-close |
| **D** | GUI null + hijack close | StormA, StormB + шум | vague → UI bug → чужой «закрой» → real close |
| **E** | /shop buy exploit + толпа | SageWire, NetherDiver, SignReader + шум | report → «у меня тоже!!» → witness → close |

---

## Сводка результатов

| Метрика | Значение |
|---------|----------|
| Всего шагов | 35 |
| Router skip (шум/чат) | 15 |
| Router bug | 18 |
| Agent completed | 16 |
| Agent timeout | **1** (B2) |
| Vague «есть бага» → skip | **2** (A1, D1) — **FAIL UX** |
| Hijack close (StormB) | skip ✓ |
| Witness append | KiteRun→RB-00016, Flint909→RB-00018, SignReader→RB-00021 ✓ |
| NetherDiver «у меня тоже!!» | update RB-00021 ✓ (dedup сработал) |

---

## Созданные / затронутые тикеты

| ID | Репортер | Тема | Мир | Исход |
|----|----------|------|-----|-------|
| RB-00016 | NovaShard | /warp shop NaN в аукционе | спавн | закрыт (+ witness KiteRun) |
| RB-00017 | IronVeil | /grave двойной лут | ванильный мир | open (остался после B2) |
| RB-00018 | IronVeil | /kit vip двойной набор | мир биомов | закрыт (+ witness Flint909) |
| RB-00019 | CopperFox | /rtp не телепортит | мир биомов | закрыт (adventure mode) |
| RB-00020 | StormA | GUI кланов null null | спавн | закрыт |
| RB-00021 | SageWire | /shop buy бесплатный алмаз | ванильный мир | закрыт (+ witnesses) |

---

## По блокам

### A — NovaShard auction ✓ (кроме vague)

| Step | Статус | Time | Заметки |
|------|--------|------|---------|
| A1-vague «есть бага» | **FAIL** | 9s | skip + PM «не баг — если что-то не работает…» |
| A2-detail | OK | 14s | Create RB-00016 + PM parallel, title `… · спавн` |
| A3-witness KiteRun | OK | 19s | Update RB-00016 + PM свидетелю |
| A4-close | OK | 9s | `[Закрыт] … · спавн` + PM |

### B — IronVeil grave/kit ⚠

| Step | Статус | Time | Заметки |
|------|--------|------|---------|
| B1-detail /grave | OK* | 24s | Create → Update → PM (3 раунда, лишний update) |
| B2-followup /kit vip | **TIMEOUT** | 49s | List→Create RB-00018→**SendGlobalMessage** (нет PM IronVeil); simulate `timeout:null` |
| B3-witness Flint909 | OK | 14s | witness→RB-00018 + PM |
| B4-close | OK | 11s | close RB-00018 + PM |

### C — CopperFox joke→real ✓

| Step | Статус | Time | Заметки |
|------|--------|------|---------|
| C1-joke | OK | 6s | skip |
| C2-vague «ладно серьёзно есть бага» | OK | 17s | router→bug, PM-вопрос (контекст после шутки помог) |
| C3-detail /rtp | OK | 33s | RB-00019 + PM |
| C4-close adventure | OK | 16s | `[Закрыт] … · мир биомов` |

### D — StormA GUI + hijack ✓ (кроме vague + title)

| Step | Статус | Time | Заметки |
|------|--------|------|---------|
| D1-vague «есть бага» | **FAIL** | 8s | skip + «не баг» PM |
| D2-ui null null | OK* | 39s | Create RB-00020 → Update+PM (2 раунда) |
| D3-hijack StormB | OK | 6s | skip, тикет не тронут |
| D4-real-close | OK | 9s | close, но title **`[Закрыт] GUI кланов null null`** без `· спавн` |

### E — SageWire shop crowd ✓

| Step | Статус | Time | Заметки |
|------|--------|------|---------|
| E1-report | OK* | 26s | survey на RB-00014 → List → Create RB-00021 → Update+PM |
| E0c NetherDiver «у меня тоже!!» | OK | 13s | ticket=new в route, agent update RB-00021 (dedup) |
| E2-witness SignReader | OK | 17s | append RB-00021 + PM |
| E3-close | OK | 11s | `[Закрыт] … · мир биомов` + PM |

---

## Новые баги (chaos)

### BUG-8 — «есть бага» → skip вместо bug survey

**Шаги:** A1 NovaShard, D1 StormA.

**Симптом:** router `skip` conf 0.75–0.8, reason «vague / not directed at bot».

**UX:** игрок **не получает** PM «че сломалось?», вместо этого SkipIntentHandler шлёт:

```
не баг — если что-то не работает, напиши команду и сервер, отвечу в личку
```

**Конtrast:** C2 CopperFox «ладно серьёзно есть бага» → bug ✓ (контекст после шутки).

**Fix:** heuristic/policy — фразы `есть бага`, `есть баг`, `баг есть` → force `bug` + survey PM; не skip.

---

### BUG-11 — SkipIntentHandler «не баг» PM на bug-like текст

**Триггер:** `looksLikeBugReport(text)` + router skip.

**Затронуто:** A1, D1, SignReader «это баг или фича» (получил тот же PM).

**Fix:** если текст матчит bug-heuristic — **не** слать «не баг»; либо молчать, либо «опиши команду и мир».

---

### BUG-9 — B2 follow-up: simulate timeout + нет PM репортеру

**Шаг:** IronVeil «ещё /kit vip…» при open survey RB-00017.

**Цепочка (~47s):**
1. depth=0 → **ListIssueTickets** (15s)
2. depth=1 → **CreateIssueTicket** RB-00018 (23s)
3. depth=2 → **UpdateIssueTicket + SendGlobalMessage** (8s) — global inquiry вместо PM

**Simulate:** `agentWait: timeout:null` при `wait_seconds=75` — chain завершился global message, не PM.

**Fix кандидаты:**
- block ListIssueTickets когда survey уже bound / ticketId известен
- после Create в survey — hint: PM primary, не global
- simulate waiter: считать global inquiry = completed
- поднять wait или ускорить chain (убрать list)

---

### BUG-10 — ListIssueTickets в survey с известным ticket

Повторяется: B2 (RB-00017 open), E1 (RB-00014 survey → list → create RB-00021).

**Fix:** BugToolPolicy — hide list когда `ticketId != null` в survey context.

---

### BUG-12 — closed title без `· {мир}`

**Шаг:** D4 StormA close RB-00020.

**Title:** `[Закрыт] GUI кланов null null` — нет `· спавн`.

**Contrast:** A4, C4, E3 закрытия с суффиксом ✓.

**Fix:** normalize closed titles так же как open (IssueTicketFormat / update path).

---

## Что работает хорошо

| Проверка | Результат |
|----------|-----------|
| Witness → существующий тикет | KiteRun, Flint909, SignReader ✓ |
| Hijack close другим игроком | StormB → skip ✓ |
| Close primary + PM | NovaShard, IronVeil RB-00018, SageWire ✓ |
| Joke detection | CopperFox «лол шучу» ✓ |
| Self-resolve close | CopperFox adventure mode ✓ |
| Player world names в title | `спавн`, `мир биомов`, `ванильный мир` ✓ |
| Create+PM parallel (A2) | 1 раунд ✓ |
| Dedup «у меня тоже!!» без деталей | NetherDiver → RB-00021 ✓ |
| Global inquiry при dup-симптоме | B2 depth=2 — функционально ок, но без PM |

---

## Latency (wall, sec)

| P50 bug step | ~14s |
| P95 bug step | ~39s |
| Max | 49s (B2 timeout) |
| Skip/noise | 3–10s |

---

## Повторный прогон

```bash
cd ~/mcserver/ProxyARC
SIM_WAIT_BUG=90 ./tools/run_chaos_simulate.sh 2>&1 | tee /tmp/chaos-sim-run.out
```

После фиксов BUG-8/9/10/11/12 — перезапустить и сравнить JSONL.
