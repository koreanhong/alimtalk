# 화면 자동 점검 (src/test/ui)

`static/index.html` 화면을 헤드리스 Chrome으로 열어, 사람 대신 클릭·입력하고 정해진 점검 항목의 PASS/FAIL을 출력한다.
서버 쪽 동작은 `./gradlew test`(JUnit)가, 화면 쪽 동작은 이 도구가 확인한다.

## 파일
- `verify.html`: 점검 항목 모음. `rec("항목 이름", 조건, 상세)` 한 줄이 점검 하나다.
- `verify.sh`: 실행기. `verify.html`을 서버의 빌드 결과 폴더에 복사하고 헤드리스 Chrome으로 열어 결과를 출력한다. 실패가 있으면 종료 코드 1.

## 실행
```bash
# 1) 가짜 LLM 서버 실행 (API 키 불필요, 비용 없음)
SPRING_PROFILES_ACTIVE=fake-llm ./gradlew bootRun --args='--server.port=8081'

# 2) 다른 터미널에서 점검 실행
src/test/ui/verify.sh
```
- 필요: macOS Google Chrome(`CHROME` 환경변수로 경로 변경 가능), python3, curl
- 실제 Claude API는 호출하지 않는다. 가짜 LLM 응답 규칙: 요청에 "광고" → 거절, "위반" → 1,100자 본문(규칙 위반), 그 외 → 주문완료 템플릿.

## 항목 추가
화면을 고칠 때는 먼저 `verify.html`에 점검 항목을 추가해 FAIL을 확인하고, 코드를 고친 뒤 전체가 PASS인지 확인한다.
