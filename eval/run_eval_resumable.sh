#!/usr/bin/env bash
# 추출 평가를 끝까지 돌린다. 중간에 꺼져도 이 스크립트를 다시 실행하면 이어서 한다.
#   - 이미 끝난 보고서는 응답 캐시(eval/.cache/extraction)에서 읽고 모델을 다시 부르지 않는다.
#   - Docker(DB), Ollama, 앱이 꺼져 있으면 켠다.
#   - 평가가 도는 동안만 caffeinate로 시스템 잠자기를 막는다(화면 꺼짐은 허용, 설정은 바꾸지 않음).
#
# 사용법 (저장소 어디서든):  eval/run_eval_resumable.sh [smoke|full]    기본값 full
# 진행 로그:                 eval/.cache/logs/<smoke|full>-latest.log  (tail -f 로 보기)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUBSET="${1:-full}"
LOG_DIR="$ROOT/eval/.cache/logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/$SUBSET-latest.log"
APP_LOG="$LOG_DIR/app.log"

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }

wait_for() {  # wait_for <설명> <최대 초> <확인 명령...>
  local what=$1 limit=$2
  shift 2
  for ((i = 0; i < limit; i += 2)); do
    "$@" >/dev/null 2>&1 && return 0
    sleep 2
  done
  log "실패: $what 이(가) ${limit}초 안에 준비되지 않았습니다"
  return 1
}

db_healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' checkup-postgres 2>/dev/null)" = healthy ]; }
ollama_up() { curl -s -m 3 localhost:11434/api/version; }
app_up() { curl -s -m 3 localhost:8080/actuator/health | grep -q UP; }

log "=== 평가 시작/재개: $SUBSET ==="

# 1) Docker Desktop + PostgreSQL
if ! docker info >/dev/null 2>&1; then
  log "Docker Desktop을 켭니다"
  open -a Docker
  wait_for "Docker" 180 docker info || exit 1
fi
docker compose -f "$ROOT/docker-compose.yml" up -d >>"$LOG" 2>&1
wait_for "PostgreSQL" 90 db_healthy || exit 1
log "DB 준비됨"

# 2) Ollama
if ! ollama_up >/dev/null; then
  log "Ollama를 켭니다"
  open -a Ollama
  wait_for "Ollama" 90 ollama_up || exit 1
fi
log "Ollama 준비됨"

# 3) 앱 (이미 떠 있으면 그대로 쓴다)
STARTED_APP=0
if ! app_up; then
  log "앱을 켭니다 (로그: $APP_LOG)"
  (cd "$ROOT/backend" && nohup ./gradlew :app:bootRun --console=plain >"$APP_LOG" 2>&1 &)
  STARTED_APP=1
  wait_for "앱" 240 app_up || exit 1
fi
log "앱 준비됨: $(curl -s localhost:8080/api/reports/extract/info)"

# 4) 평가 (캐시 덕분에 다시 실행하면 끝난 보고서는 건너뛴다)
cd "$ROOT/eval" || exit 1
caffeinate -is .venv/bin/python -u -m extraction.run_eval --subset "$SUBSET" --format pdf 2>&1 | tee -a "$LOG"
STATUS=${PIPESTATUS[0]}

# 5) 이 스크립트가 켠 앱만 끈다
if [ "$STARTED_APP" = 1 ]; then
  pkill -f 'com.checkupai.CheckupAiApplication'
  log "앱을 껐습니다"
fi
log "=== 끝 (종료 코드 $STATUS) ==="
exit "$STATUS"
