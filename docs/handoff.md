# 작업 인수인계 (Handoff)

새 세션은 이 문서를 먼저 확인하고 작업을 이어간다. 작업이 끝나면 아래 "현재 상태", "결정 사항", "미결정 사항", "다음 작업", "작업 이력"을 갱신한다.

## 현재 상태 (2026-11-12 기준)
- 기능: 자연어("주문 관련 메시지 세팅해줘") → Claude API가 카카오 알림톡 규칙에 맞는 템플릿 초안 생성 → 등록 폼 자동 채움. 수작업으로는 카카오 제공 유형 전부 설정 가능.
- git: 브랜치 `feature/nl-template-generation` (main에는 초기 커밋만). 원격 저장소 없음.
- 구현 계획 Task 1~8 완료. Task 8(실제 Claude 호출 확인): 2026-11-13 테스트 15건 모두 기대대로, 재생성 0회, 응답 2~11초, 건당 약 $0.009(추정).
- 테스트: 서버 자동 테스트 66개 중 65개 통과, 1개 skip(실제 Claude 호출 테스트). 화면 자동 점검(`src/test/ui/`) 47항목 통과.
- 실제 Claude API 호출 성공(2026-11-13, 크레딧 충전 후). 첫 호출: input 39 / output 252 / cache write 5,007 토큰, 추정 약 $0.03.

### 주요 파일
| 경로 | 내용 |
|---|---|
| `docs/specs/2026-11-12-nl-alimtalk-template-design.md` | 설계 문서(승인됨, 이후 변경 반영) |
| `docs/plans/2026-11-12-nl-alimtalk-template.md` | 구현 계획(Task 1~8) |
| `docs/run-guide.md` | 실행 가이드(키 설정→서버 실행, 테스트, 에러 확인, 자연어 테스트 케이스) |
| `src/main/resources/rules/alimtalk-rules.json` | 규칙 수치(코드 점검용), `GET /api/rules`로 화면에도 제공 |
| `src/main/resources/rules/alimtalk-review-guide.md` | Claude 생성 지침(카카오 심사 가이드 요약) |
| `src/main/java/com/alimtalk/` | `rules/`(규칙 로드·점검), `llm/`(Claude 연동·프롬프트·가짜 LLM), `template/`(생성 서비스·API) |
| `src/main/resources/static/index.html` | 화면(원본 시안 `alimtalk-template.html`을 고친 것, 원본은 그대로 둠) |
| `.claude/agents/` | 프로젝트 에이전트: architect, developer, code-reviewer(model: fable), test |
| `src/test/ui/` | 화면 자동 점검 도구(`verify.html` 점검 항목, `verify.sh` 실행기, `README.md`). 가짜 LLM 서버(8081)를 띄운 뒤 `src/test/ui/verify.sh` |
| `.superpowers/sdd/2026-11-12-nl-alimtalk-template/` | 구현 진행 기록(ledger). git 제외 |

### 실행 방법
- 상세 명령·에러 확인·자연어 테스트 케이스: **`docs/run-guide.md`**
- 실제 Claude: 같은 터미널에서 `read -s ANTHROPIC_API_KEY && export ANTHROPIC_API_KEY`(키 붙여넣기) 후 `./gradlew bootRun 2>&1 | tee build/live-run.log` → http://localhost:8080. 새 창마다 키 재입력
- 가짜 LLM: `SPRING_PROFILES_ACTIVE=fake-llm ./gradlew bootRun` (요청에 "위반"→규칙 위반, "광고"→거절, 그 외→주문완료 고정 응답)
- 화면 점검: `SPRING_PROFILES_ACTIVE=fake-llm ./gradlew bootRun --args='--server.port=8081'` 후 `src/test/ui/verify.sh`
- 테스트: `./gradlew test` / 실제 호출 테스트: `LIVE_LLM_TEST=true ./gradlew test --tests '*ClaudeLlmClientLiveTest'`(비용 발생)

## 결정 사항
### 프로젝트 운영
- `CLAUDE.md` 섹션 1·2는 내용이 정해지면 채운다. 그 전에는 임의로 작성하지 않는다.
- 인수인계 문서는 `docs/handoff.md`, `CLAUDE.md`에서 `@docs/handoff.md`로 참조. 작업이 끝날 때마다 갱신.
- 코드 변경·작업 전에 사용자 확인을 받는다. 확인·승인·선택은 선택형 질문(AskUserQuestion)으로 묻는다(둘 다 CLAUDE.md 4번).
- 설계 문서는 `docs/specs/`, 구현 계획은 `docs/plans/`에 둔다.

### 기능 범위
- 알림톡만. 브랜드 메시지(광고)·발송 설정(수신 대상·시점) 자동화는 이번 범위 아님.
- 자동 작성 범위: 메시지 유형 기본형·부가정보형, 강조 유형 선택 안 함·강조 표기형만. 버튼·아이템리스트는 자동 작성 시 비움. 자연어 입력 카드에 범위 안내 문구 표시.
- 수작업: 메시지 유형 4종(BA/EX/AD/MI), 강조 유형 4종(NONE/TEXT/IMAGE/ITEM_LIST), 버튼 섹션 모두 설정 가능.
- "검수 요청 데이터 생성"·검증 결과 영역은 화면에서 제거. 규칙은 생성 조건 + 생성 후 자동 점검(위반 시 최대 2회 재생성)에만 사용.
- 재생성 후에도 위반이면 위반 내용 표시 + [그래도 폼에 채우기]/[다시 생성].
- 애매한 요청(예: "주문 관련 메시지")은 되묻지 않고 가장 일반적인 경우로 작성하고, 가정한 내용을 경고 맨 앞에 표시(사용자 결정 B). 되묻기(UNCLEAR)는 무엇을 안내할지 전혀 모를 때만.
- 채널추가형 안내 문구: "채널 추가하고 이 채널의 광고와 마케팅 메시지 받기"(사용자 결정, 카카오 원문과 다름).

### 규칙
- 내용 기준: 카카오 비즈니스 알림톡 심사 가이드. 형식 수치: 딜러사 확정 전까지 확인된 값 중 엄격한 값(변수 30개 등).
- 규칙 파일은 로컬(classpath `src/main/resources/rules/`)에 두고 수동 갱신, 변경 시 재빌드·재배포.
- 아이템리스트형 규격: 네이버 클라우드 SENS 문서 기준, 대행사 확정 시 맞춤. 버튼·아이템리스트 제한값도 `alimtalk-rules.json`(`buttons`, `itemList`)에서 관리하고 화면은 `/api/rules`로 읽는다.

### 기술
- Java 21(Gradle toolchain 자동 다운로드), Spring Boot 3.5.16(사용자 결정 3.x, start.spring.io는 4.x만 제공), Gradle 9.8.2 wrapper.
- Claude API: 공식 Java SDK `anthropic-java` 3.8.0, 모델 `claude-opus-5-5`, effort medium, 구조화 출력, 시스템 프롬프트 캐싱.
- API 키: 개인 Claude Console 발급, 환경변수 `ANTHROPIC_API_KEY`로만 사용.
- 시간 제한: 호출당 60초, 요청 전체 120초. SDK 재시도는 그대로 둔다(사용자 결정) — 60초 초과 시 백그라운드 재요청으로 추가 과금 가능. 바꾸려면 `ClaudeLlmClient`에서 `maxRetries(0)`.
- 서버측 refusal fallback은 Java SDK에 문서화된 방법이 없어 미적용.
- Claude 응답의 상태·메시지 유형·강조 유형은 enum(구조화 출력 스키마에서 허용값만). 화면 계약(TemplateDraft)은 문자열 유지.
- 채널: 등록된 3개만 이름을 프롬프트에 넣고, 미등록 값은 빈 이름, 50자 초과는 400.
- API 키가 없으면 서버 시작 시 WARN 로그, 생성 요청은 502(LLM_ERROR).
- 사용량 기록: 생성 요청 1건마다 `logs/llm-usage.csv`에 1줄(결과·시도·소요 시간·토큰 4종·추정비용·모델·카테고리·유형·요청 문장). 비용은 `application.yml`의 단가로 계산한 추정치(Opus 5.5: 입력 $4, 출력 $20, 캐시 읽기 $0.20, 캐시 저장 $5 /100만 토큰). 기록 실패는 생성 응답에 영향 없음. `logs/`는 git 제외.

## 미결정 사항 (사용자 확인 필요)
- 딜러사(발송 대행사) 선정 → 규칙 수치·아이템리스트 규격 확정
- 주요 사용자(내부 운영자 / 외부 고객사), DB, 배포 환경
- Spring Boot 3.5 지원 기간 확인 후 4.x 전환 여부
- `CLAUDE.md` 섹션 1·2 작성 시점

## 다음 작업
1. (완료) Task 8 실제 Claude 호출 확인 — 결과는 현재 상태·작업 이력 참고. 키 설정·실행 순서는 `docs/run-guide.md` 1장
2. 남은 보류 지적 2건(이전 버전 저장값 때문의 불필요한 확인 창, 저장 데이터 일부 손상 시 보정) 처리 여부 결정
3. 브랜치를 main에 합칠지 결정

## 작업 이력
- 2026-11-12: CLAUDE.md 파악, `docs/handoff.md` 생성, "작업 종료 시 handoff 갱신" 규칙 추가.
- 2026-11-12: 시안 분석, 자연어 템플릿 자동 작성 기획(브레인스토밍) → 설계 문서 승인 → 구현 계획 작성.
- 2026-11-12: 구현 Task 1~7 완료(git init, Spring Boot 골격, 규칙 점검기, Claude 연동, 재생성 서비스, API, 화면). 최종 코드 리뷰: Critical 0, Important 2(스왑 파일 커밋 → 제거 / SDK 재시도 → 그대로 두기로 결정), Minor 7 보류.
- 2026-11-12: 사용자 요청으로 수작업 기능 복원(메시지 유형 4종, 강조 유형 4종 + 아이템리스트형 신규, 버튼), 자동 작성 범위 안내 추가, 채널추가 문구 변경.
- 2026-11-12: CLAUDE.md에 "코드 변경·작업 전 사용자 확인" 규칙 추가. 프로젝트 에이전트 4종(`.claude/agents/`) 생성 — 세션 재시작 후 인식 확인.
- 2026-11-12: 2차 코드 리뷰(code-reviewer 에이전트, 리뷰 이후 변경분): Critical 0, Important 0, Minor 9. 사용자 선택으로 1(하이라이트 변수 글자 모양)·2(자동 작성 시 이미지 비우기)·3(버튼 초과 표시)·8(버튼·아이템리스트 제한값 규칙 파일 이동) 수정. CLAUDE.md에 "선택형 질문으로 확인" 규칙 추가. handoff 재정리.
- 2026-11-12: 보류 지적 중 사용자 선택 항목 처리 — 덮어쓰기 취소 시 결과 보관, 자연어 글자 수 기준, 특수문자 변수명, 유형 값 enum 강제, 채널 값 제한, API 키 없을 때 경고·502 통일. 화면 점검 도구를 `src/test/ui/`로 옮기고 test 에이전트 지침 연결. 커밋 메시지는 한국어로 작성(사용자 요청).
- 2026-11-12: CLAUDE.md에 "커밋 메시지는 한국어" 규칙 추가. Task 8은 API 키 발급 대기로 보류.
- 2026-11-12: 사용자 API 키 발급 완료. 처음 `export`로 입력해 셸 히스토리에 남았을 수 있어 사용자가 직접 히스토리 정리(사용자 결정, 키 재발급은 안 함). Task 8 진행 순서를 `read -s` 방식으로 변경.
- 2026-11-13: 실행 가이드 `docs/run-guide.md` 작성(키 설정→서버 실행, 테스트, 에러 확인, 자연어 테스트 케이스 15개). handoff 실행 방법을 `read -s` 방식으로 수정. 키 관리 방식(키체인 등)은 사용자가 나중에 결정.
- 2026-11-13: 실제 호출 400 오류. 원인 파악을 위해 `ClaudeLlmClient`가 Anthropic 오류 종류·설명(300자까지)·요청 ID를 WARN 로그에 남기도록 수정(키·요청 본문은 남기지 않음, 화면 응답은 그대로).
- 2026-11-13: 크레딧 충전 후 실제 호출 성공. "주문 관련 메시지"가 UNCLEAR(되묻기)로 응답 → 사용자 결정으로 애매한 요청은 가장 일반적인 경우로 작성 + 가정 문구를 경고 맨 앞에 표시하도록 생성 지침·LlmOutput 설명·생성 서비스 수정(테스트 3개 추가).
- 2026-11-13: 실행 가이드 5장 테스트 15건 실제 Claude로 실행 → 15/15 기대대로(재생성 0회, 2~11초, 15건 약 $0.13 추정). 생성 요청별 사용량 CSV 기록(`logs/llm-usage.csv`) 추가(테스트 12개 추가, 가짜 LLM 서버로 기록 확인).
