#!/bin/bash

# ─────────────────────────────────────────────────────────────────────────────
# ralph-continuous.sh — never-ending supervisor around ralph.sh for droidputter.
#
# Runs ralph.sh in fixed-size batches forever: pauses between successful batches,
# backs off on API errors (rate limit / overloaded / 429 / 503 / capacity), and
# (optionally) pings Telegram on batch start / complete / error / done. Stops on
# Ctrl+C, or when the PRD reaches 100% (ralph.sh prints "PRD COMPLETE").
#
# Engine: first arg, or ENGINE env var, default claude.
#   ./ralph-continuous.sh                     # claude engine
#   ./ralph-continuous.sh codex               # gpt-5.6-sol via codex
#   BATCH_SIZE=3 BATCH_PAUSE_MINS=15 WAIT_TIME_MINS=15 ./ralph-continuous.sh
# ─────────────────────────────────────────────────────────────────────────────

CYAN='\033[0;36m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'
RED='\033[0;31m'; MAGENTA='\033[0;35m'; BLUE='\033[0;34m'
BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'

# Load git-ignored local config if present (provider keys, TELEGRAM_*, BATCH_SIZE…)
if [ -f .env.ralph.local ]; then set -a; . ./.env.ralph.local; set +a; fi

# Engine: positional arg wins, then env, then claude.
if [ -n "$1" ]; then ENGINE="$1"; fi
ENGINE="${ENGINE:-claude}"
case "$ENGINE" in
  claude|codex) ;;
  *) echo "Unknown engine '$ENGINE' (expected claude, codex)"; exit 1 ;;
esac
export ENGINE

# Config (override with env vars)
BATCH_SIZE=${BATCH_SIZE:-3}
WAIT_TIME_MINS=${WAIT_TIME_MINS:-15}         # backoff after API errors
BATCH_PAUSE_MINS=${BATCH_PAUSE_MINS:-15}     # pause between successful batches
WAIT_TIME_SECS=$((WAIT_TIME_MINS * 60))
BATCH_PAUSE_SECS=$((BATCH_PAUSE_MINS * 60))
export PRD_FILE="${PRD_FILE:-spec.json}"

case "$ENGINE" in
  claude) ENGINE_DESC="claude (${MODEL:-CLI default})" ;;
  codex)  ENGINE_DESC="codex (${CODEX_MODEL:-gpt-5.6-sol})" ;;
esac

# Telegram (optional). Priority: env var > OpenClaw config file. Empty token = off.
OPENCLAW_CONFIG="${HOME}/.openclaw/openclaw.json"
if [ -z "${TELEGRAM_BOT_TOKEN:-}" ] && [ -f "$OPENCLAW_CONFIG" ]; then
  TELEGRAM_BOT_TOKEN=$(jq -r '.channels.telegram.botToken // empty' "$OPENCLAW_CONFIG" 2>/dev/null || echo "")
fi
TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN:-""}
TELEGRAM_CHAT_ID=${TELEGRAM_CHAT_ID:-""}  # set via env; empty = notifications off

send_telegram() {
  local message="$1"
  if [ -n "$TELEGRAM_BOT_TOKEN" ]; then
    curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
      -d "chat_id=${TELEGRAM_CHAT_ID}" \
      -d "text=${message}" \
      -d "parse_mode=Markdown" >> /tmp/telegram-debug.log 2>&1
  fi
}

batch_count=0
total_iterations=0
runner_start=$(date +%s)

cleanup() {
  echo ""
  echo -e "${YELLOW}${BOLD}Interrupted. Exiting...${NC}"
  exit 1
}
trap cleanup INT TERM

format_time() {
  local secs=$1
  printf "%02d:%02d:%02d" $((secs/3600)) $((secs%3600/60)) $((secs%60))
}

print_progress_bar() {
  local current=$1; local total=$2; local width=40
  local percent=$((current * 100 / total))
  local filled=$((current * width / total))
  local empty=$((width - filled))
  printf "${YELLOW}["
  printf "%${filled}s" | tr ' ' '█'
  printf "%${empty}s" | tr ' ' '░'
  printf "] ${percent}%%${NC}"
}

clear
echo ""
echo -e "${MAGENTA}${BOLD}╔═══════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${MAGENTA}${BOLD}║   📟  DROIDPUTTER — RALPH CONTINUOUS RUNNER${NC}"
printf  "${MAGENTA}${BOLD}║   🤖 Engine:         %-40s${NC}\n" "$ENGINE_DESC"
printf  "${MAGENTA}${BOLD}║   📦 Batch size:     %-3s iterations${NC}\n" "$BATCH_SIZE"
printf  "${MAGENTA}${BOLD}║   ⏸️  Batch pause:    %-3s minutes${NC}\n" "$BATCH_PAUSE_MINS"
printf  "${MAGENTA}${BOLD}║   ⏰ Wait on error:  %-3s minutes${NC}\n" "$WAIT_TIME_MINS"
printf  "${MAGENTA}${BOLD}║   📄 PRD:            %-30s${NC}\n" "$PRD_FILE"
echo -e "${MAGENTA}${BOLD}║   🕐 Started at:     $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo -e "${MAGENTA}${BOLD}╚═══════════════════════════════════════════════════════════════════╝${NC}"
echo ""

while true; do
  batch_count=$((batch_count + 1))
  batch_start=$(date +%s)

  echo ""
  echo -e "${CYAN}${BOLD}┌───────────────────────────────────────────────────────────────────┐${NC}"
  echo -e "${CYAN}${BOLD}│  ▶ BATCH #${batch_count}   📅 $(date '+%Y-%m-%d %H:%M:%S')   🔄 ${BATCH_SIZE} iterations   🤖 ${ENGINE}${NC}"
  echo -e "${CYAN}${BOLD}└───────────────────────────────────────────────────────────────────┘${NC}"
  echo ""

  send_telegram "📟 *DROIDPUTTER Ralph [${ENGINE}]* - Batch #${batch_count} Starting

📊 Current: $(./progress.sh)
🔄 Running ${BATCH_SIZE} iterations..."

  # Run ralph.sh and capture output + exit code
  tmplog=$(mktemp)
  ./ralph.sh $BATCH_SIZE 2>&1 | tee "$tmplog"
  exit_code=${PIPESTATUS[0]}

  api_error=false
  error_msg=""
  error_detail=""

  # SUCCESS first — claude's JSON success markers override error detection.
  if [ "$ENGINE" = "claude" ] && grep -q '"is_error":false' "$tmplog" && grep -q '"subtype":"success"' "$tmplog"; then
    api_error=false
  elif [ $exit_code -ne 0 ]; then
    api_error=true
    if grep -qi "rate limit\|rate_limit\|ratelimit" "$tmplog"; then error_msg="Rate limit hit"
    elif grep -qi "hit your limit" "$tmplog"; then error_msg="You've hit your limit"
    elif grep -qi "overloaded" "$tmplog"; then error_msg="API overloaded"
    elif grep -qi "Too Many Requests" "$tmplog"; then error_msg="HTTP 429 (Too Many Requests)"
    elif grep -qi "Service Unavailable" "$tmplog"; then error_msg="HTTP 503 (Service Unavailable)"
    elif grep -qi "at capacity" "$tmplog"; then error_msg="API at capacity"
    elif grep -qi "No messages returned" "$tmplog"; then error_msg="No messages returned"
    elif grep -qi "ECONNREFUSED\|connection refused" "$tmplog"; then error_msg="Connection refused"
    elif grep -qi "host verify FAILED" "$tmplog"; then error_msg="Host verification failed — URGENT task injected"
    else error_msg="Unknown error (exit code: $exit_code)"
    fi
    error_detail=$(grep -i "error\|failed\|refused\|limit" "$tmplog" | grep -v "is_error.*false" | tail -3 | head -c 300)
  fi

  # PRD complete?
  prd_complete=false
  if grep -q "PRD COMPLETE" "$tmplog" 2>/dev/null; then
    prd_complete=true
  fi

  rm -f "$tmplog"

  batch_end=$(date +%s)
  batch_time=$((batch_end - batch_start))
  total_iterations=$((total_iterations + BATCH_SIZE))

  if [ "$api_error" = true ]; then
    resume_time=$(date -d "+${WAIT_TIME_MINS} minutes" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date -v+${WAIT_TIME_MINS}M '+%Y-%m-%d %H:%M:%S')
    echo ""
    echo -e "${RED}${BOLD}   🚨 API ERROR: ${error_msg}${NC}"
    echo -e "${RED}${BOLD}   ⏸️  Pausing ${WAIT_TIME_MINS} min — resume at ${resume_time}${NC}"
    echo ""

    if [ -n "$error_detail" ]; then
      send_telegram "🚨 *DROIDPUTTER Ralph [${ENGINE}]* - API Error

❌ ${error_msg}
📝 \`${error_detail:0:200}\`
⏸️ Pausing ${WAIT_TIME_MINS} min · Resume ${resume_time}
📊 $(./progress.sh)"
    else
      send_telegram "🚨 *DROIDPUTTER Ralph [${ENGINE}]* - API Error

❌ ${error_msg}
⏸️ Pausing ${WAIT_TIME_MINS} min · Resume ${resume_time}
📊 $(./progress.sh)"
    fi

    if [ -t 1 ]; then
      remaining=$WAIT_TIME_SECS; total_wait=$WAIT_TIME_SECS
      while [ $remaining -gt 0 ]; do
        elapsed=$((total_wait - remaining))
        echo -ne "\r${YELLOW}${BOLD}   ⏳ Waiting: ${NC}${YELLOW}$(format_time $remaining)${NC}  "
        print_progress_bar $elapsed $total_wait
        echo -ne "  ${DIM}Resume: ${resume_time}${NC}   "
        sleep 1; remaining=$((remaining - 1))
      done
      echo ""
    else
      # Detached (tmux >> log): one line and one sleep — no per-second countdown spam.
      echo "   ⏳ Waiting ${WAIT_TIME_MINS} min — resume at ${resume_time}"
      sleep $WAIT_TIME_SECS
    fi
    echo -e "${GREEN}${BOLD}   ✅ Wait complete! Resuming...${NC}"; echo ""
  else
    resume_time=$(date -d "+${BATCH_PAUSE_MINS} minutes" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date -v+${BATCH_PAUSE_MINS}M '+%Y-%m-%d %H:%M:%S')
    echo ""
    echo -e "${GREEN}${BOLD}   ✅ BATCH #${batch_count} done in $(format_time $batch_time) · 📊 $(./progress.sh)${NC}"
    echo ""

    send_telegram "✅ *DROIDPUTTER Ralph [${ENGINE}]* - Batch #${batch_count} Complete

⏱️ $(format_time $batch_time)
📊 $(./progress.sh)
⏸️ Next batch in ${BATCH_PAUSE_MINS} min"

    if [ -t 1 ]; then
      remaining=$BATCH_PAUSE_SECS; total_pause=$BATCH_PAUSE_SECS
      while [ $remaining -gt 0 ]; do
        elapsed=$((total_pause - remaining))
        echo -ne "\r${YELLOW}   ⏸️  Breathing space: ${NC}${YELLOW}$(printf '%02d:%02d' $((remaining/60)) $((remaining%60)))${NC}  "
        print_progress_bar $elapsed $total_pause
        echo -ne "  ${DIM}Next batch: ${resume_time}${NC}   "
        sleep 1; remaining=$((remaining - 1))
      done
      echo ""
    else
      # Detached (tmux >> log): one line and one sleep — no per-second countdown spam.
      echo "   ⏸️  Pausing ${BATCH_PAUSE_MINS} min — next batch at ${resume_time}"
      sleep $BATCH_PAUSE_SECS
    fi
    echo -e "${GREEN}   ✅ Starting next batch...${NC}"; echo ""
  fi

  if [ "$prd_complete" = true ]; then
    runner_end=$(date +%s)
    total_time=$((runner_end - runner_start))
    echo ""
    echo -e "${GREEN}${BOLD}╔═══════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}${BOLD}║   🎉  DROIDPUTTER PRD COMPLETE!${NC}"
    echo -e "${GREEN}${BOLD}║   📦 Batches: ${batch_count}   🔄 Iterations: ${total_iterations}   ⏱️ $(format_time $total_time)${NC}"
    echo -e "${GREEN}${BOLD}║   📊 $(./progress.sh)${NC}"
    echo -e "${GREEN}${BOLD}╚═══════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    send_telegram "🎉 *DROIDPUTTER — PRD COMPLETE!* 🎉

🤖 Engine: ${ENGINE}
📦 Batches: ${batch_count}
🔄 Iterations: ${total_iterations}
⏱️ $(format_time $total_time)
📊 $(./progress.sh)

The phone is the Cardputer. 📟🚀"
    exit 0
  fi
done
