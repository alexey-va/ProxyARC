#!/usr/bin/env bash
# Complex multi-player simulate scenarios on Velocity.
set -euo pipefail

TOKEN="${PROXYARC_OPS_TOKEN:-2328457b88fda38e72e2e85d60badcb5fa3238a082aec258}"
HOST="${MC_VELOCITY_HOST:-velocity}"
WAIT_BUG="${SIM_WAIT_BUG:-75}"
WAIT_SKIP="${SIM_WAIT_SKIP:-20}"
LOG="/tmp/chaos-sim-$(date +%s).log"
RESULTS="/tmp/chaos-sim-results.jsonl"

: >"$LOG"
: >"$RESULTS"

sim() {
  local id="$1" body="$2"
  echo "" | tee -a "$LOG"
  echo "========== $id ==========" | tee -a "$LOG"
  echo "$body" | tee -a "$LOG"
  local t0
  t0=$(date +%s)
  local out
  out=$(ssh "$HOST" "curl -sS -m 120 -H 'Authorization: Bearer $TOKEN' -H 'Content-Type: application/json' -d '$body' http://127.0.0.1:25825/ops/skorin/simulate")
  local dt=$(($(date +%s) - t0))
  echo "TIME: ${dt}s" | tee -a "$LOG"
  echo "$out" | python3 -m json.tool 2>/dev/null | tee -a "$LOG" || echo "$out" | tee -a "$LOG"
  echo "$out" >>"$RESULTS"
  sleep 2
}

noise() {
  local id="$1" player="$2" msg="$3" server="${4:-classic}"
  sim "$id" "{\"player\":\"$player\",\"message\":\"$msg\",\"server\":\"$server\",\"wait_seconds\":$WAIT_SKIP}"
}

bug() {
  local id="$1" player="$2" msg="$3" server="${4:-classic}" reply="${5:-false}"
  sim "$id" "{\"player\":\"$player\",\"message\":\"$msg\",\"server\":\"$server\",\"reply_to_bot\":$reply,\"wait_seconds\":$WAIT_BUG}"
}

echo "Chaos simulate started $(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "$LOG"

# ─── SCENARIO A: auction NaN + witness pile-on ───
echo ">>> SCENARIO A: NovaShard auction crash + witnesses" | tee -a "$LOG"
noise "A0a" "PixelFox" "го в войс кто тут на спавне"
noise "A0b" "QuartzLM" "продаю алмазы дёшево пм"
noise "A0c" "MoonByte" "лол скорен опять молчит"
bug "A1-vague" "NovaShard" "есть бага" "classic" false
noise "A0d" "PixelFox" "скорен ты жив?"
bug "A2-detail" "NovaShard" "/warp shop крашит клиент, в GUI аукциона NaN вместо цены" "classic" true
bug "A3-witness" "KiteRun" "у меня тоже NaN в меню аукциона на спавне, после /warp shop" "classic" false
noise "A0e" "QuartzLM" "кто идёт на паркур"
bug "A4-close" "NovaShard" "починилось закрой тикет" "classic" true

# ─── SCENARIO B: survival dup + second symptom + witness ───
echo ">>> SCENARIO B: IronVeil grave/kit dup" | tee -a "$LOG"
noise "B0a" "SandHop" "кто трейд?"
noise "B0b" "RuneBell" "++"
noise "B0c" "AshTrail" "скорен расскажи анекдот"
bug "B1-detail" "IronVeil" "/grave иногда выдаёт двойной лут в ванильном мире" "survival" false
noise "B0d" "SandHop" "ironveil ты где"
bug "B2-followup" "IronVeil" "ещё /kit vip выдаёт набор два раза подряд на мир биомов" "survival" true
bug "B3-witness" "Flint909" "у меня kit vip тоже дублирует предметы на мир биомов" "survival" false
noise "B0e" "RuneBell" "gg всем"
bug "B4-close" "IronVeil" "всё ок закрой тикет" "survival" true

# ─── SCENARIO C: joke then real bug same player ───
echo ">>> SCENARIO C: CopperFox joke → real rtp" | tee -a "$LOG"
bug "C1-joke" "CopperFox" "rtp не работает лол шучу бро" "classic" false
noise "C0a" "DriftWood" "привет всем"
bug "C2-vague" "CopperFox" "ладно серьёзно есть бага" "classic" false
bug "C3-detail" "CopperFox" "/rtp не телепортит в мир биомов, просто пишет ошибку" "classic" true
bug "C4-close" "CopperFox" "забей нашёл — стоял в adventure mode" "classic" true

# ─── SCENARIO D: cross-player close attempt + UI bug ───
echo ">>> SCENARIO D: StormA GUI + StormB hijack close" | tee -a "$LOG"
noise "D0a" "EchoBolt" "кто на данжах"
bug "D1-vague" "StormA" "есть бага" "classic" false
bug "D2-ui" "StormA" "в GUI кланов вместо названия null null на спавне" "classic" true
bug "D3-hijack" "StormB" "закрой тикет всё норм" "classic" false
noise "D0b" "EchoBolt" "storma ты тикет завёл?"
bug "D4-real-close" "StormA" "да починилось закрой тикет" "classic" true

# ─── SCENARIO E: global inquiry chaos (if agent sends global) ───
echo ">>> SCENARIO E: SageWire shop exploit + crowd" | tee -a "$LOG"
noise "E0a" "NetherDiver" "shop buy алмаз бесплатно??"
noise "E0b" "SignReader" "это баг или фича"
bug "E1-report" "SageWire" "/shop buy выдаёт алмазный блок бесплатно каждый раз в ванильном мире" "survival" false
noise "E0c" "NetherDiver" "у меня тоже!!"
bug "E2-witness" "SignReader" "подтверждаю shop buy на survival дюпает алмазы" "survival" false
bug "E3-close" "SageWire" "ок закрылся сам баг закрой тикет" "survival" true

echo "" | tee -a "$LOG"
echo "========== LOG SNIPPET (tools) ==========" | tee -a "$LOG"
ssh "$HOST" "grep -E 'NovaShard|IronVeil|CopperFox|StormA|StormB|SageWire|KiteRun|Flint909|SignReader|CreateIssueTicket|UpdateIssueTicket|SendPrivateMessage|SendGlobalMessage|Route bug|survey_witness|chain complete|detail fallback|Skipping update' ~/velocity/logs/latest.log | tail -80" | tee -a "$LOG"

echo "" | tee -a "$LOG"
echo "Done. Log: $LOG Results: $RESULTS"
