#!/bin/bash
# 화면 자동 점검: 가짜 LLM 서버가 떠 있는 상태에서 실행한다.
#   SPRING_PROFILES_ACTIVE=fake-llm ./gradlew bootRun --args='--server.port=8081'
#   src/test/ui/verify.sh            (포트 변경: PORT=8080 src/test/ui/verify.sh)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PORT="${PORT:-8081}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
STATIC="$ROOT/build/resources/main/static"
TMP="$ROOT/build/tmp/ui-verify"

[ -d "$STATIC" ] || { echo "서버를 먼저 띄워 주세요 ($STATIC 없음)"; exit 2; }
curl -s -o /dev/null "http://localhost:$PORT/api/rules" || { echo "http://localhost:$PORT 에 서버가 없습니다"; exit 2; }

# 실행 중인 서버가 바로 읽을 수 있도록 빌드 결과 폴더에 복사한다(소스 폴더에는 넣지 않음)
cp "$ROOT/src/test/ui/verify.html" "$STATIC/__verify.html"
rm -rf "$TMP" && mkdir -p "$TMP"
"$CHROME" --headless=new --disable-gpu --no-first-run --user-data-dir="$TMP/profile" \
  --virtual-time-budget=30000 --dump-dom "http://localhost:$PORT/__verify.html" 2>/dev/null > "$TMP/dom.html"

grep -o 'DONE .*\]' "$TMP/dom.html" | sed 's/^DONE //' | python3 -c "
import json, sys, html
r = json.loads(html.unescape(sys.stdin.read()))
for x in r:
    print(('PASS' if x['pass'] else 'FAIL'), x['name'], '|', x['detail'][:60])
fails = sum(not x['pass'] for x in r)
print('TOTAL', len(r), 'FAIL', fails)
sys.exit(1 if fails else 0)
"
