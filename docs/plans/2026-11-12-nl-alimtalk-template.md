# 자연어 알림톡 템플릿 자동 작성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 자연어 요청("주문 관련 메시지 세팅해줘")을 받아 카카오 알림톡 규칙에 맞는 템플릿 폼 값을 Claude로 생성·점검해 돌려주는 Spring Boot 서버와, 이를 쓰는 기존 HTML 화면을 만든다.

**Architecture:** 단일 Gradle 모듈 Spring Boot 앱. classpath의 규칙 파일(`rules/`)을 기동 시 로드하고, `LlmClient` 인터페이스 뒤에 Claude Java SDK 구현을 둔다. 생성 서비스가 LLM 호출 → `RuleValidator` 점검 → 위반 시 재생성 루프를 돌리고, 컨트롤러가 200/422/502/504로 응답한다. 프론트는 기존 HTML을 정적 리소스로 옮겨 수정한다.

**Tech Stack:** Java 21, Spring Boot 3.x(생성 시점 최신 3.x 안정판), Gradle(Kotlin DSL), Jackson, JUnit 5 + Spring Boot Test(MockMvc), Anthropic Java SDK `com.anthropic:anthropic-java`(README 기준 3.3.0, 구현 시 최신 안정판 확인), 프론트는 순수 HTML/JS.

**Spec:** `docs/specs/2026-11-12-nl-alimtalk-template-design.md`

## Global Constraints

- Java 21, Spring Boot 3.x, Gradle. 프론트엔드 프레임워크 도입 금지.
- 모델 `claude-opus-5-5`. thinking 파라미터를 보내지 않는다(이 모델은 끌 수 없음, disabled 시 400). effort는 `OutputConfig.Effort.MEDIUM`으로 시작(설정값으로 변경 가능).
- API 키는 환경변수 `ANTHROPIC_API_KEY`로만. `application.yml`, 코드, 로그, 응답에 키를 넣지 않는다.
- 규칙 파일 위치: `src/main/resources/rules/alimtalk-rules.json`, `src/main/resources/rules/alimtalk-review-guide.md`.
- 생성 대상 메시지 유형은 `BA`, `EX`만. 강조 유형은 `NONE`, `TEXT`만. 버튼 생성 없음.
- 재생성: 최대 2회 재시도(총 3회 호출). 호출 1회 타임아웃 60초, 요청 전체 예산 120초, 남은 예산 60초 미만이면 재시도하지 않음.
- 응답 필드 이름은 기존 HTML `state` 객체와 동일: `category, name, code, msgType, emph, title, sub, content, extra, security, samples`.
- 실제 Claude API 호출 테스트는 기본 `./gradlew test`에서 실행되지 않는다(`ANTHROPIC_API_KEY`와 `LIVE_LLM_TEST=true`가 모두 있을 때만).
- 패키지 루트: `com.alimtalk` (스펙상 빌더 재량).

## Review Focus

1. **사용자가 폼에 이미 입력한 상태에서 생성** — 덮어쓰기 전 확인 창이 떠야 하고, 취소하면 폼이 그대로여야 한다. → Task 7 수동 검증 항목.
2. **예시값에 긴 문자열이 들어간 변수** — 본문 자체는 짧아도 예시값 치환 후 1,000자를 넘으면 오류여야 한다. → Task 3 `contentLengthCountsSampleValues` 테스트.
3. **LLM이 일부 필드를 빈 문자열/누락으로 반환** (예: `EX`인데 `extra` 빈값, `TEXT`인데 `sub` 빈값) — 예외가 아니라 규칙 오류로 잡혀 재생성되어야 한다. → Task 3 `extraRequiredForEx`, `emphasisTextRequiresTitleAndSub` 테스트, Task 5 `nullFieldsAreTreatedAsEmpty` 테스트.
4. **Claude 응답 지연으로 예산 소진** — 1차 호출이 70초 걸리면 재시도 없이 판정해야 한다(무한 대기·180초 초과 금지). → Task 5 `stopsRetryingWhenBudgetLow` 테스트.
5. **공백만 있거나 501자 이상인 prompt** — 400으로 거절되고 LLM은 호출되지 않아야 한다(비용 발생 방지). → Task 6 `blankPromptIs400`, `tooLongPromptIs400` 테스트.

---

## 사전 준비 (Task 1 전에 사용자 확인 필요)

- 로컬 JDK는 현재 17. Java 21이 필요하다 → Gradle toolchain 자동 다운로드(foojay resolver) 또는 사용자가 JDK 21 설치. **사용자 선택.**
- Gradle 미설치 → start.spring.io에서 wrapper 포함 프로젝트를 받아 사용(외부 다운로드).
- 저장소가 git이 아님 → 각 Task의 Commit 단계는 `git init` 여부를 사용자가 정한 뒤에만 수행. 하지 않으면 Commit 단계는 건너뛴다.

---

### Task 1: 프로젝트 골격 + 규칙 파일 로드 + `GET /api/rules`

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradlew`, `gradle/wrapper/*` (start.spring.io: Gradle-Kotlin, Java 21, Spring Boot 3.x, dependencies `web`, `validation`)
- Create: `src/main/java/com/alimtalk/AlimtalkApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/rules/alimtalk-rules.json`
- Create: `src/main/java/com/alimtalk/rules/RuleSet.java`, `RuleLoader.java`, `RulesController.java`
- Create: `.gitignore` (build/, .gradle/, .idea/, *.iml, .env)
- Test: `src/test/java/com/alimtalk/rules/RuleLoaderTest.java`, `RulesControllerTest.java`

**Interfaces:**
- Produces:
  - `record RuleSet(Meta meta, Limits limits, List<String> categories, List<String> msgTypes, List<String> emphTypes, List<String> bannedPhrases, List<String> cautionWords, String codePattern, int promptLengthTarget)` — `Limits(int content, int extra, int title, int sub, int name, int code, int vars)`, `Meta(List<String> source, String checkedAt, String note)`
  - `@Component RuleLoader` with `RuleSet ruleSet()` (기동 시 1회 로드) and `String rawJson()`
  - `GET /api/rules` → `alimtalk-rules.json` 원문 그대로(`application/json`)

`alimtalk-rules.json` 값(스펙 6.1.1 그대로):
- `limits`: content 1000, extra 500, title 50, sub 50, name 40, code 30, vars 30
- `categories`: `002001, 002002, 003001, 004001, 006001, 006003, 999999`
- `msgTypes`: `BA, EX` / `emphTypes`: `NONE, TEXT`
- `codePattern`: `^[A-Za-z0-9_]{1,30}$`
- `bannedPhrases`(오류): `지금 구매`, `앱 설치`, `특가`, `(광고)` / `cautionWords`(경고): `할인`, `이벤트`, `쿠폰`, `혜택`, `광고`
- `promptLengthTarget`: 700
- `meta.source`: 카카오 심사 가이드 URL, Solapi 문서 URL, NCP 문서 URL / `checkedAt`: `2026-11-12`

`application.yml`: `alimtalk.llm.model: claude-opus-5-5`, `alimtalk.llm.effort: medium`, `alimtalk.llm.call-timeout: 60s`, `alimtalk.llm.total-budget: 120s`, `alimtalk.llm.max-retries: 2`, `alimtalk.llm.max-tokens: 16000`. 키 항목 없음.

- [ ] **Step 1: start.spring.io로 골격 생성, `./gradlew --version`이 Java 21 toolchain으로 동작하는지 확인**
- [ ] **Step 2: 실패 테스트 작성** — `RuleLoaderTest.loadsLimitsFromClasspath`: `ruleSet().limits().content()==1000`, `vars()==30`, `msgTypes()==List.of("BA","EX")`, `categories().size()==7`. `RulesControllerTest.returnsRawRulesJson`: `GET /api/rules` → 200, `$.limits.content == 1000`.
- [ ] **Step 3: `./gradlew test` → FAIL 확인**
- [ ] **Step 4: 규칙 JSON, `RuleSet`, `RuleLoader`(Jackson으로 classpath 읽기), `RulesController` 구현**
- [ ] **Step 5: `./gradlew test` → PASS 확인**
- [ ] **Step 6: Commit** (`feat: scaffold spring boot app and load alimtalk rules`)

---

### Task 2: 생성 지침 초안 `alimtalk-review-guide.md` (사용자 검토 게이트)

**Files:**
- Create: `src/main/resources/rules/alimtalk-review-guide.md`

**Interfaces:**
- Produces: classpath 리소스 `rules/alimtalk-review-guide.md` (Task 4가 시스템 프롬프트에 그대로 넣음)

내용 구성(스펙 6.1):
1. 맨 위: 출처 URL(https://kakaobusiness.gitbook.io/main/ad/infotalk/audit 및 발송 가능 예시 페이지), 확인일
2. 정보성 메시지 정의, "형식화 가능" 원칙, 고객 행동 → 기업 피드백 구조
3. 정보성으로 보는 경우 / 발송 불가 예시(심사 가이드 요약)
4. 카테고리별(구매완료, 구매취소, 배송상태, 예약완료, 회원가입, 인증) 작성 예시 1개씩 — 변수 `#{...}`와 예시값 포함
5. 작성 규칙: 본문 700자 이내 목표, 부가정보·강조 보조문구에 변수 금지, 광고 문구 금지

- [ ] **Step 1: 심사 가이드와 발송 가능 예시 페이지를 확인해 초안 작성** (원문에 없는 수치·사례는 만들지 않음)
- [ ] **Step 2: 사용자에게 초안 검토 요청 — 승인 전에는 Task 4 이후로 진행하지 않는다** (Task 3은 이 파일에 의존하지 않으므로 병행 가능)
- [ ] **Step 3: 사용자 피드백 반영 후 Commit** (`docs: add alimtalk review guide for llm`)

---

### Task 3: `RuleValidator`

**Files:**
- Create: `src/main/java/com/alimtalk/template/TemplateDraft.java`
- Create: `src/main/java/com/alimtalk/rules/RuleValidator.java`, `ValidationResult.java`
- Test: `src/test/java/com/alimtalk/rules/RuleValidatorTest.java`

**Interfaces:**
- Consumes: `RuleSet` (Task 1)
- Produces:
  - `record TemplateDraft(String category, String name, String code, String msgType, String emph, String title, String sub, String content, String extra, boolean security, Map<String,String> samples)`
  - `record ValidationResult(List<String> errors, List<String> warnings)` with `boolean hasErrors()`
  - `RuleValidator(RuleSet)` / `ValidationResult validate(TemplateDraft d)`
  - `static List<String> variablesIn(String s)` — `#\{([^{}]*)\}` 매칭, 등장 순서, 중복 제거

규칙(스펙 6.1.1, 수준 그대로). 메시지는 한국어 한 문장, 수치 포함(예: `"본문이 1,050자입니다 (최대 1,000자)"`) — Task 5의 재요청에 그대로 쓰인다.
- 합산 길이 = `title + sub + content + extra`(해당 유형일 때만), 변수는 `samples` 값으로 치환 후 `codePointCount` 기준
- 변수 대상: `content`, (`TEXT`일 때) `title`. `sub`·`extra`에 변수가 있으면 오류.

- [ ] **Step 1: 실패 테스트 작성** — 각 테스트는 유효한 기본 draft(`BA`, `NONE`, 짧은 본문, 변수 1개+예시값)에서 한 필드만 바꾼다:
  - `validDraftHasNoErrors`
  - `contentLengthCountsSampleValues`: 본문 900자 + `#{상품명}` 예시값 200자 → 오류에 `"최대 1,000자"` 포함
  - `extraRequiredForEx`: `EX` + `extra=""` → 오류
  - `extraMustNotContainVariables`, `subMustNotContainVariables`
  - `emphasisTextRequiresTitleAndSub`: `TEXT` + `sub=""` → 오류
  - `variableCountOver30IsError`: 변수 31개
  - `missingSampleIsError`: `samples`에 키 없음 또는 빈 값
  - `emptyVariableNameIsError`: `#{}`
  - `variablesOnlyContentIsError`: `"#{a} #{b}"`
  - `invalidCodeIsError`: `"주문-01"`
  - `unknownCategoryIsError`, `adMsgTypeIsError`(`AD`), `imageEmphIsError`(`IMAGE`)
  - `bannedPhraseIsError`: 본문에 `"지금 구매"`
  - `cautionWordIsWarningOnly`: 본문에 `"할인 금액: #{할인금액}"` → errors 비어 있음, warnings 1개
  - `securityWithoutAuthIsWarning`
  - `nullStringFieldsTreatedAsEmpty`: `extra=null`인 `BA` → NPE 없이 통과
- [ ] **Step 2: `./gradlew test --tests '*RuleValidatorTest'` → FAIL**
- [ ] **Step 3: `RuleValidator` 구현** (기존 HTML `validate()`를 출발점으로, 위 차이점 반영)
- [ ] **Step 4: 같은 명령 → PASS**
- [ ] **Step 5: Commit** (`feat: validate template drafts against alimtalk rules`)

---

### Task 4: `LlmClient` + Claude 구현 + 프롬프트 구성

**Files:**
- Create: `src/main/java/com/alimtalk/llm/LlmClient.java`, `LlmOutput.java`, `LlmException.java`, `PromptBuilder.java`, `ClaudeLlmClient.java`, `LlmProperties.java`
- Modify: `build.gradle.kts` (add `implementation("com.anthropic:anthropic-java:<최신 안정판>")`)
- Modify: `src/main/java/com/alimtalk/rules/RuleLoader.java` (add `String reviewGuide()`)
- Test: `src/test/java/com/alimtalk/llm/PromptBuilderTest.java`, `src/test/java/com/alimtalk/llm/ClaudeLlmClientLiveTest.java`

**Interfaces:**
- Consumes: `RuleSet`, `TemplateDraft`, `RuleLoader` (리뷰 가이드 원문 로드 메서드 `String reviewGuide()`를 Task 1의 `RuleLoader`에 추가)
- Produces:
  - `interface LlmClient { LlmOutput generate(String system, String user, Duration timeout) throws LlmException; }`
  - `record LlmOutput(String status /* OK | AD_CONTENT | UNCLEAR */, String message, DraftOut draft)` — `DraftOut`은 `TemplateDraft`와 같은 필드이되 `samples`를 `List<Sample> samples`(`record Sample(String name, String value)`)로 둔다(구조화 출력 스키마에서 Map 대신 배열). `DraftOut.toDraft()` → `TemplateDraft`.
  - `LlmException(Kind kind, String message)` — `Kind { TIMEOUT, API_ERROR, REFUSAL, INVALID_OUTPUT }`
  - `PromptBuilder(RuleLoader)`: `String system()` (리뷰 가이드 전문 + 규칙 수치 요약 + 카테고리 코드·이름 목록 + `promptLengthTarget`), `String user(String prompt, String channelName)`, `String retry(String prompt, String channelName, TemplateDraft previous, List<String> errors)`
  - `@ConfigurationProperties("alimtalk.llm") record LlmProperties(String model, String effort, Duration callTimeout, Duration totalBudget, int maxRetries, long maxTokens)`

`ClaudeLlmClient` 구현 기준(Java SDK 문서 기준, 정확한 메서드는 컴파일러로 확인):
- 클라이언트: `AnthropicOkHttpClient.fromEnv()` (빈으로 1회 생성)
- 요청: `MessageCreateParams.builder().model(props.model()).maxTokens(props.maxTokens()).systemOfTextBlockParams(List.of(TextBlockParam(system, cacheControl=CacheControlEphemeral)))`, `.outputConfig(LlmOutput.class)`로 구조화 출력, effort는 `OutputConfig.Effort`로 설정(구조화 출력 typed builder와 effort 동시 설정 방법은 SDK에서 확인), `.thinking(...)` 호출 없음
- 서버측 refusal fallback(`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`)을 기본 적용 — Java 빌더 메서드는 SDK 저장소 `examples/`에서 확인. 확인이 안 되면 적용하지 않고 `stop_reason == refusal`을 `LlmException(REFUSAL)`로 처리
- 타임아웃: 호출별 `timeout` 인자로 요청 옵션 지정. SDK 타임아웃 예외 → `TIMEOUT`, 그 외 API 예외 → `API_ERROR`. 예외 메시지에 키·헤더를 담지 않는다.

- [ ] **Step 1: 실패 테스트 작성** — `PromptBuilderTest`:
  - `systemContainsReviewGuideAndTargets`: `system()`에 리뷰 가이드 첫 줄, `"700"`, 카테고리 코드 `002001` 포함, `"1000"`(실제 제한)은 포함하지 않음
  - `systemIsStableAcrossCalls`: 두 번 호출 결과 동일(캐시 무효화 방지)
  - `retryIncludesErrorsAndPrevious`: `retry(...)`에 오류 문장들과 이전 `content` 포함
- [ ] **Step 2: `./gradlew test --tests '*PromptBuilderTest'` → FAIL**
- [ ] **Step 3: `PromptBuilder`, `LlmOutput`, `LlmException`, `LlmProperties`, `ClaudeLlmClient` 구현**
- [ ] **Step 4: 같은 명령 → PASS, `./gradlew compileJava` 성공**
- [ ] **Step 5: 라이브 테스트 작성** — `ClaudeLlmClientLiveTest.generatesOrderTemplate`: `@EnabledIfEnvironmentVariable(named="LIVE_LLM_TEST", matches="true")`, 실제 호출 결과 `status=="OK"`, `draft.msgType()`가 `BA|EX`. (실행은 Task 8)
- [ ] **Step 6: `./gradlew test` → 라이브 테스트는 SKIPPED로 표시되는지 확인**
- [ ] **Step 7: Commit** (`feat: add claude llm client with structured output`)

---

### Task 5: `TemplateGenerationService` (재생성 루프·시간 예산)

**Files:**
- Create: `src/main/java/com/alimtalk/template/TemplateGenerationService.java`, `GenerationResult.java`
- Test: `src/test/java/com/alimtalk/template/TemplateGenerationServiceTest.java` (가짜 `LlmClient`, 가짜 `Clock`)

**Interfaces:**
- Consumes: `LlmClient`, `PromptBuilder`, `RuleValidator`, `LlmProperties`, `TemplateDraft`, `ValidationResult`
- Produces:
  - `sealed interface GenerationResult` with records `Success(TemplateDraft draft, List<String> warnings, int attempts)`, `Rejected(String reason /* AD_CONTENT | UNCLEAR */, String message)`, `RuleViolation(TemplateDraft draft, List<String> errors, List<String> warnings, int attempts)`
  - `GenerationResult generate(String prompt, String channelName) throws LlmException`
  - 생성자에 `java.time.Clock` 주입(테스트에서 시간 진행 제어)

동작: 1회차 `user()`, 이후 `retry()`. `status != OK`면 즉시 `Rejected`. 오류 없으면 `Success`. 재시도 조건 = 오류 있음 && 시도 < 1+maxRetries && 남은 예산 ≥ callTimeout. 호출 timeout = min(callTimeout, 남은 예산). `LlmException`은 첫 결과가 하나도 없을 때만 그대로 던지고, 이전 draft가 있으면 마지막 draft로 `RuleViolation`.

- [ ] **Step 1: 실패 테스트 작성**
  - `firstAttemptSuccess`: attempts 1
  - `retriesThenSuccess`: 1회차 오류 → 2회차 통과, attempts 2, 2회차 user 메시지에 1회차 오류 문장 포함
  - `ruleViolationAfterThreeAttempts`: 3회 모두 오류 → `RuleViolation`, attempts 3, 마지막 draft 반환
  - `stopsRetryingWhenBudgetLow`: 1회차가 시계를 70초 진행시키고 오류 → 재시도 없이 `RuleViolation`, attempts 1
  - `adContentIsRejectedWithoutValidation`
  - `timeoutOnFirstCallPropagates`: `LlmException(TIMEOUT)` 그대로 throw
  - `timeoutAfterAPreviousDraftReturnsViolation`
  - `nullFieldsAreTreatedAsEmpty`: LLM이 `extra=null` 반환해도 예외 없음
- [ ] **Step 2: `./gradlew test --tests '*TemplateGenerationServiceTest'` → FAIL**
- [ ] **Step 3: 구현**
- [ ] **Step 4: 같은 명령 → PASS**
- [ ] **Step 5: Commit** (`feat: generate templates with rule-checked retries`)

---

### Task 6: `POST /api/templates/generate`

**Files:**
- Create: `src/main/java/com/alimtalk/template/TemplateController.java`, `GenerateRequest.java`, `ApiErrorHandler.java`
- Test: `src/test/java/com/alimtalk/template/TemplateControllerTest.java` (`@WebMvcTest`, 서비스 목)

**Interfaces:**
- Consumes: `TemplateGenerationService.generate`, `GenerationResult`, `LlmException`
- Produces: HTTP 계약(스펙 6.2)
  - 요청 `GenerateRequest(@NotBlank @Size(max=500) String prompt, String channel)` — 채널명은 컨트롤러에서 채널 코드 → 표시명 매핑(`@mystore→마이스토어`, `@hotvpn→HotVPN`, `@testchannel→테스트채널`)
  - 200 `{ draft, warnings, attempts }`
  - 422 `{ reason, message, draft?, errors?, warnings? }` — `RuleViolation`이면 `reason=RULE_VIOLATION`, message `"규칙을 모두 맞추지 못했습니다."`, draft·errors·warnings 포함. `Rejected`면 reason·message만.
  - 400: 검증 실패 `{ reason: "BAD_REQUEST", message }`
  - 504: `TIMEOUT` → `{ reason: "TIMEOUT", message: "응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요." }`
  - 502: `API_ERROR`, `REFUSAL`, `INVALID_OUTPUT` → `{ reason: "LLM_ERROR", message: "템플릿 생성 중 오류가 발생했습니다." }` (내부 메시지 노출 금지)

- [ ] **Step 1: 실패 테스트 작성** — `successReturns200WithDraft`(`$.draft.msgType`, `$.attempts`), `ruleViolationReturns422WithDraft`(`$.reason=="RULE_VIOLATION"`, `$.draft.content`, `$.errors[0]`), `adContentReturns422WithoutDraft`(`$.draft` 없음), `blankPromptIs400`(`"   "`, 서비스 호출 0회 검증), `tooLongPromptIs400`(501자), `timeoutIs504`, `apiErrorIs502WithoutInternalMessage`(응답 본문에 예외 메시지 문자열 미포함)
- [ ] **Step 2: `./gradlew test --tests '*TemplateControllerTest'` → FAIL**
- [ ] **Step 3: 구현**
- [ ] **Step 4: `./gradlew test` 전체 → PASS**
- [ ] **Step 5: Commit** (`feat: expose template generation api`)

---

### Task 7: 화면 (`static/index.html`)

**Files:**
- Create: `src/main/resources/static/index.html` (`alimtalk-template.html` 복사 후 수정. 원본 파일은 삭제하지 않는다)
- Create: `src/main/java/com/alimtalk/llm/FakeLlmClient.java` — `@Profile("fake-llm")`, 프롬프트에 `"위반"` 포함 시 본문 1,100자 draft, `"광고"` 포함 시 `AD_CONTENT`, 그 외 고정 주문완료 draft 반환. `ClaudeLlmClient`는 `@Profile("!fake-llm")`.

변경(스펙 6.5):
- 상단에 자연어 입력 카드: `<textarea id="nlPrompt" maxlength="500">`, `[생성]` 버튼, 상태 영역 `#nlStatus`
- 생성 중: 버튼 비활성화, `"생성 중입니다. 최대 2분 정도 걸릴 수 있습니다."`, `fetch`에 `AbortController` 130초
- 200: 폼에 입력 내용이 있으면(`state`가 `SAMPLE`과 다르면) `confirm("현재 입력한 내용을 덮어씁니다. 계속할까요?")`, 확인 시 `state`에 `draft` 대입 + `buttons: []`, `fillForm(); update();`, warnings 표시
- 422 `RULE_VIOLATION`: errors 목록 + `[그래도 폼에 채우기]`(위와 같은 덮어쓰기 흐름, errors는 계속 표시) + `[다시 생성]`
- 422 기타 / 400 / 502 / 504: `message`만 표시
- 제거: 버튼 섹션과 관련 JS(`renderButtons`, `addBtn`, 미리보기 버튼 렌더), "검수 요청 데이터 생성" 버튼, `#result` 영역과 `validate()`·`payload()`·복사 JS, 메시지 유형 `AD`·`MI` 라디오, 강조 유형 `IMAGE` 라디오와 이미지 입력
- 카운터 제한값은 기동 시 `GET /api/rules`의 `limits`로 `LIMIT` 덮어쓰기(실패 시 기존 값 유지)
- 상단 `"로컬 Dry-run 모드"` 표시 → `"자동 작성 모드"`
- localStorage 키를 `alimtalk-tpl-draft-v2`로 변경(이전 버전의 `AD`/`IMAGE` 상태 복원 방지)
- "예시로 초기화"는 유지. `SAMPLE`에서 `buttons` 제거(버튼 섹션이 없으므로)

- [ ] **Step 1: `index.html` 작성**
- [ ] **Step 2: `SPRING_PROFILES_ACTIVE=fake-llm ./gradlew bootRun` 후 브라우저로 수동 검증** — 각 항목 결과를 기록:
  - 페이지 로드, 콘솔 오류 없음, 버튼 섹션·검수 영역·AD/MI/IMAGE 선택지 없음
  - "주문 관련 메시지 세팅해줘" → 폼·미리보기 채워짐
  - 폼 수정 후 재생성 → 확인 창, 취소 시 폼 유지 (Review Focus 1)
  - "위반" 포함 입력 → 오류 목록 + 두 버튼, [그래도 폼에 채우기] 후 본문 카운터 초과 표시
  - "광고" 포함 입력 → 안내 메시지만
  - 빈 입력 → 400 메시지
- [ ] **Step 3: `./gradlew test` → PASS (FakeLlmClient 추가로 기존 테스트 깨지지 않음)**
- [ ] **Step 4: Commit** (`feat: natural language template generation ui`)

---

### Task 8: 실제 Claude 호출 확인 (사용자 API 키 필요)

**Files:** 없음 (확인만)

- [ ] **Step 1: 사용자에게 `ANTHROPIC_API_KEY` 설정 요청** (키는 대화·파일에 적지 않고 사용자가 셸에서 export)
- [ ] **Step 2: `LIVE_LLM_TEST=true ./gradlew test --tests '*ClaudeLlmClientLiveTest'` → PASS, 응답 시간·토큰 사용량(캐시 읽기 포함) 기록**
- [ ] **Step 3: `./gradlew bootRun`으로 화면에서 "주문 관련 메시지 세팅해줘", "배송 시작 알림 만들어줘", "회원가입 인증번호 보내는 거" 3건 실행, 결과·재시도 횟수·소요 시간을 `docs/handoff.md`에 기록**
- [ ] **Step 4: 결과를 사용자에게 보고하고 effort 조정 필요 여부 확인**
