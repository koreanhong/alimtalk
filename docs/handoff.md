# 작업 인수인계 (Handoff)

새 세션은 이 문서를 먼저 확인하고 작업을 이어간다. 작업이 끝나면 아래 "현재 상태", "결정 사항", "다음 작업", "작업 이력"을 갱신한다.

## 현재 상태
- 제로베이스 단계. 소스 코드, 설정 파일, 의존성 파일 없음. git 저장소 아님.
- 존재하는 파일
  - `CLAUDE.md`: 프로젝트 규칙. 섹션 1(개요)·2(기술 스택)는 틀만 있고 내용 미정.
  - `alimtalk-template.html`: "알림톡 템플릿 등록" 화면 단일 HTML 시안.
    - 구성: 왼쪽 입력 폼(기본 정보, 메시지 유형, 검증 결과) + 오른쪽 휴대폰 대화방 미리보기, 다크모드 지원.
    - 상단 구조만 확인했으며 전체 로직(입력 항목, 검증 규칙 등)은 아직 분석하지 않음.
  - `docs/handoff.md`: 이 문서.

## 결정 사항
- `CLAUDE.md` 섹션 1·2는 내용이 정해지면 채운다. 그 전에는 임의로 작성하지 않는다.
- 인수인계 문서는 `docs/handoff.md`에 두고, `CLAUDE.md`에서 `@docs/handoff.md`로 참조한다.
- 작업이 끝날 때마다 이 문서를 갱신한다. 별도 요청이 없어도 한다.

### 기획: 자연어 → 알림톡 템플릿 폼 자동 작성 (브레인스토밍 진행 중, 설계 문서 미작성)
- 범위: 알림톡만. 브랜드 메시지(광고)는 추후. "주문 관련 메시지 세팅해줘" 같은 자연어로 기존 템플릿 등록 폼을 자동으로 채운다.
  - 발송 설정(수신 대상·시점) 자동화는 이번 범위가 아님 (추후 단계 후보).
  - 버튼 섹션은 제외. 이미지형 강조는 자동 생성하지 않음(제안).
- 규칙 기준: 내용 = 카카오 비즈니스 알림톡 심사 가이드(https://kakaobusiness.gitbook.io/main/ad/infotalk/audit), 형식(글자 수 등) = 딜러사 API 문서(딜러사 미정).
  - 규칙은 매번 가져오지 않고 로컬 파일로 관리: `rules/alimtalk-rules.json`(코드 점검용), `rules/alimtalk-review-guide.md`(LLM 생성 지침). 출처·확인일 기재, 수동 갱신.
  - 규칙은 생성 시 조건 + 생성 후 자동 점검(위반 시 재생성)에 공통 사용. 별도 검수 화면은 두지 않음.
  - 기존 시안 규칙과 차이 확인됨: 1,000자는 강조·부가정보 포함/변수 치환 후 기준(Solapi), 변수 개수 40(공식) vs 30(NCP), 강조 보조문구 변수 불가.
- 구현 방식: LLM 생성 + 규칙 점검 (선택지 A, 사용자 선택).
- 백엔드: Java + Spring Boot. LLM: Claude API(공식 Java SDK, 구조화 출력), 모델 `claude-opus-5-5`.
- API 키: 사용자가 개인 Claude Console에서 발급 예정. 서버 환경변수 `ANTHROPIC_API_KEY`로만 사용, 저장소에 넣지 않음.
- 구조 제안: 기존 HTML을 Spring Boot 정적 리소스로 사용 + 자연어 입력창 추가 → `POST /api/templates/generate` → 폼 JSON 응답.

## 미결정 사항 (사용자 확인 필요)
- Java / Spring Boot 버전, 빌드 도구 (제안: Java 21, Spring Boot 3.x, Gradle — 미확정)
- 딜러사(발송 대행사) 선정 → 형식 규칙 수치 확정
- 주요 사용자: 내부 운영자 / 외부 고객사
- DB, 배포 환경

## 다음 작업 후보
- Java/Spring Boot 버전·빌드 도구 확정
- 설계 문서 `docs/specs/2026-11-12-nl-alimtalk-template-design.md` **승인됨(2026-11-12)**.
- 구현 계획 `docs/plans/2026-11-12-nl-alimtalk-template.md` 작성됨(Task 1~8). 다음: 사용자 계획 검토·실행 방식 선택 → 구현
  - 실행 방식: Native(이 세션에서 순서대로 구현). 진행 기록(ledger): `.superpowers/sdd/2026-11-12-nl-alimtalk-template/progress.md` (git 제외 폴더)
  - 환경: git init 완료, 작업 브랜치 `feature/nl-template-generation`. JDK 21은 Gradle toolchain(foojay) 자동 다운로드, Gradle 9.8.2 wrapper.
  - Spring Boot: start.spring.io가 4.x만 제공 → 사용자 결정(3.x)을 지켜 `3.5.16`(Maven Central 최신 3.5.x)으로 고정. 3.5의 지원 기간은 확인 필요.
  - 진행 상황: Task 1(골격·규칙 JSON·`GET /api/rules`) 완료, Task 3(RuleValidator, 테스트 18개) 완료.
  - Task 1~7 완료(2026-11-12). 자동 테스트 41개(라이브 1개 skip), 가짜 LLM 모드 + 헤드리스 Chrome 화면 확인 24항목 통과.
  - 실행 모드: `./gradlew bootRun`(실제 Claude, `ANTHROPIC_API_KEY` 필요) / `SPRING_PROFILES_ACTIVE=fake-llm ./gradlew bootRun`(가짜 LLM: "위반"→규칙 위반, "광고"→거절, 그 외→주문완료 고정 응답)
  - 최종 코드 리뷰: Critical 0, Important 2(스왑 파일 커밋 → 제거 완료 / 타임아웃 후 SDK 백그라운드 재시도로 추가 과금 가능 → **사용자 결정 대기**), Minor 7(ledger에 기록, 보류)
  - 2026-11-12 변경(사용자 요청): 수작업으로는 카카오 제공 유형 전부 설정 가능 — 메시지 유형 4종(BA/EX/AD/MI), 강조 유형 4종(NONE/TEXT/IMAGE/ITEM_LIST), 버튼 섹션 복원. 자동 작성은 BA/EX·NONE/TEXT만, 자연어 카드에 안내 문구(#nlScope). 화면 확인 37항목 통과.
  - 아이템리스트형 규격: 네이버 클라우드 기준 유지, 대행사 확정 시 맞춤(사용자 결정).
  - SDK 재시도: 사용자 결정(재확인됨) "(나) 그대로 둔다" — 응답 60초 초과 시 SDK가 백그라운드로 최대 2회 재요청(과금 가능). 변경하려면 ClaudeLlmClient에서 maxRetries(0).
  - 남은 작업: Task 8(사용자 API 키로 실제 호출 확인). 확인 필요: 채널추가형 안내 문구가 시안("…광고와 마케팅 메시지를…")과 카카오 원문("채널 추가하고 이 채널의 마케팅 메시지 등을 카카오톡으로 받기")이 다름.
  - 규칙 파일 위치: `src/main/resources/rules/`(classpath, 사용자 결정)
  - 생성 지침 `alimtalk-review-guide.md`: 구현 첫 단계에서 Claude가 초안 작성 → 사용자 검토 후 진행
  - 재생성 후에도 규칙 위반 시: 위반 내용 표시 + [그래도 폼에 채우기]/[다시 생성]
- 설계 문서는 `docs/specs/`에 둔다(사용자 결정).
- 위 미결정 사항 확정 후 `CLAUDE.md` 섹션 1·2 작성
- 프로젝트 골격 생성 (대규모 변경이므로 사전 승인 필요)

## 작업 이력
- 2026-11-12: CLAUDE.md 및 프로젝트 현황 파악. `docs/handoff.md` 생성, `CLAUDE.md`에 참조 한 줄 추가. "작업 종료 시마다 handoff 갱신" 규칙 확정 및 `CLAUDE.md` 4번 섹션에 추가. 규칙은 `CLAUDE.md`에서만 관리한다(개인 메모리의 중복 항목은 삭제).
- 2026-11-12: CLAUDE.md 4번 섹션에 "코드 변경·작업 전 사용자 확인" 규칙 추가. 채널추가형 문구를 "채널 추가하고 이 채널의 광고와 마케팅 메시지 받기"로 변경(사용자 결정). 프로젝트 에이전트 4종 생성: `.claude/agents/`(architect, developer, code-reviewer[model: fable], test) — 새 세션부터 인식됨. 리뷰 이후 변경분(수작업 기능·아이템리스트형) 에이전트 검토 진행 중.
- 2026-11-12: 구현 시작 — git init, Task 1·3 완료(테스트 20개 통과), Task 2 생성 지침 초안 작성 후 사용자 검토 대기.
- 2026-11-12: `alimtalk-template.html` 전체 분석(Dry-run, 페이로드 생성만). 자연어 템플릿 자동 작성 기획 브레인스토밍 — 범위·규칙 관리 방식·구현 방식(A)·백엔드·모델 결정.
