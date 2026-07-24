# Ручные и live интеграционные тесты Скорена (OpenRouter)

Проверяют **реальный** роутер: промпт `src/main/resources/prompts/router.txt` и тот же user-payload, что `RouterContext.toUserContent()` в Kotlin.

## Подготовка

```bash
cd ProxyARC/tools/router_eval
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

export OPENROUTER_API_KEY="sk-or-..."   # из modules/llm.yml на velocity
export ROUTER_MODEL="deepseek/deepseek-v4-flash"   # опционально

# Если без прокси (локально):
export OPENROUTER_PROXY_ENABLED=false   # только для Kotlin liveTest
```

На проде OpenRouter часто идёт через HTTP-проки из `llm.yml`. Python eval ходит напрямую — с локальной машины может нужен VPN/проки или ключ с доступом.

## 1. Автоматический strict suite (OpenRouter)

```bash
python3 eval_router.py -v
# или с паузой между запросами (rate limit):
python3 eval_router.py --delay 2 -v
```

Кейсы: `cases.json` — strict assert `intent == expected`, ответ не пустой.

## 2. Ручной прогон сценариев (OpenRouter + чеклист в игре)

```bash
# Пошагово с паузой — читать шаги в игре и сверять intent
python3 run_manual.py --pause --suite manual

# Все manual + strict cases
python3 run_manual.py --suite all --delay 1.5
```

Сценарии: `manual_scenarios.json` — title, шаги в Minecraft, acceptance criteria, live OpenRouter call.

## 3. pytest live

```bash
pytest -m live -v          # нужен OPENROUTER_API_KEY
pytest -m "not live" -v    # только offline parser
```

## 4. Kotlin liveTest (тот же код, что на velocity)

```bash
cd ProxyARC
export OPENROUTER_API_KEY="sk-or-..."
export OPENROUTER_PROXY_ENABLED=false   # или true + OPENROUTER_PROXY_HOST/PORT

./gradlew liveTest
```

Использует `AssistantRouter` + `OpenRouterRouterLlmGateway` + `RouteDecisionPolicy` — полный путь как в проде.

## 5. Ручной чеклист в игре (после деплоя)

| # | Действие | Ожидание |
|---|----------|----------|
| A | `rtp не работает` (без !) | Личка: «завёл тикет RB-xxxxx»; в паблик тишина |
| B | Ответ на личку: «в мире биомов» | update тикета; личка «записал»; не chat в паблик |
| C | Open ticket + `!скорен ку` | bug-сценарий, не «починилось или как» в паблик |
| D | `!скорен ку` → ответ → `!нет не починили` | chat continuation, ответ в паблик |
| E | `скорен ку` без ! | skip или bug, не chat |
| F | Контекст в логах/observe | `Скорен » текст` без `[скорен]` и без `[ответ скорену]` |

Логи на velocity:

```bash
grep -E 'Router classify|Route bug|личка|Assistant' velocity/logs/latest.log | tail -40
```

## Файлы

| Файл | Назначение |
|------|------------|
| `router.py` | OpenRouter client + payload как в Kotlin |
| `cases.json` | Strict regression (CI optional) |
| `manual_scenarios.json` | Сценарии с шагами в игре |
| `eval_router.py` | Batch strict eval |
| `run_manual.py` | Interactive manual + live API |
| `test_router.py` | pytest offline + `@pytest.mark.live` |

## Добавить кейс

1. Добавить объект в `cases.json` (для автоматики).
2. Для ручного — дублировать в `manual_scenarios.json` с `steps_in_game` и `acceptance`.
3. Для Kotlin — скопировать `cases.json` в `src/test/resources/router_eval/cases.json`.
