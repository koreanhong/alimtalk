# 대화형 알림톡 템플릿 세팅 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 자연어 한 번에 채우기를 "질문·선택지로 이어지는 대화형 세팅(새로 만들기 + 다듬기)"으로 바꾼다.

**Architecture:** 화면이 대화 기록과 현재 폼을 매 턴 `POST /api/templates/chat`으로 보내고, 서버는 기존 생성 루프(규칙 점검·재생성·시간 예산·사용량 기록)를 여러 턴 입력으로 확장해 Claude 응답(질문/작성/거절)을 돌려준다. 새로 만들기·다듬기 판단은 Claude가 하고(`mode`), 다듬기 적용은 화면에서 사용자 확인 후 텍스트 항목만 바꾼다.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Gradle, Anthropic Java SDK 3.8.0(`claude-opus-5-5`, 구조화 출력), 순수 HTML/JS, JUnit 5 + MockMvc, 헤드리스 Chrome 화면 점검(`src/test/ui/`).

**Spec:** `docs/specs/2026-11-13-conversational-setup-design.md` (선행: `docs/specs/2026-11-12-nl-alimtalk-template-design.md`)

## Global Constraints

- 자동 세팅 범위는 그대로: 메시지 유형 `BA`·`EX`, 강조 유형 `NONE`·`TEXT`. 버튼·이미지·아이템리스트·채널추가형은 자동으로 바꾸지 않는다.
- 생성 지침 하나로 통일: 애매하면 질문(ASK). 기존 `/api/templates/generate`는 남기고, ASK는 `422 {reason:"UNCLEAR"}`로 응답한다.
- 질문만 연속 3번이면 다음 Claude 호출에 "이번에는 반드시 작성" 지시를 추가한다. 대화는 최대 20개 발화, 발화당 1~500자, 마지막은 `user`.
- 선택지는 질문당 최대 6개, 각 20자 이내.
- 다듬기 결과는 화면에서 [적용]/[취소] 확인 후에만 폼에 반영하고, 텍스트 항목만 바꾼다(버튼·이미지·아이템리스트·채널 유지). 폼 유형이 자동 범위 밖(AD/MI/IMAGE/ITEM_LIST)이면 유형은 폼 것을 유지한다.
- `/chat`은 규칙 위반도 200 + `errors`로 응답한다. 400/502/504는 기존 `ApiErrorHandler` 그대로.
- 사용량 기록은 턴마다 1줄, 열 끝에 `모드`·`대화턴수` 추가, 기본 경로 `logs/llm-usage-v2.csv`.
- 시스템 프롬프트는 요청마다 동일(캐시 유지). 현재 폼·강제 작성 지시는 메시지 쪽에 넣는다.
- 실제 Claude 호출(비용)은 기본 테스트에서 하지 않는다.
- **커밋은 각 Task 끝에서 변경 파일·메시지를 보여 주고 사용자 승인 후** 한다(CLAUDE.md). 커밋 메시지는 한국어.

## 역할 분담 [사용자 결정]
- Task 1~4(서버): 메인 세션에서 구현.
- Task 5(화면): test 에이전트(점검 먼저) → frontend-developer 에이전트(구현) → test 에이전트(확인). Task 4 후 세션 재시작.
- 마지막: code-reviewer 에이전트가 전체 검토. Task 6: 메인 세션(실제 호출은 승인 후).

## Review Focus

1. **예시 템플릿이 채워진 첫 화면에서 "배송 안내 만들어줘" 입력** — 다듬기가 아니라 새로 만들기로 처리되어야 한다(Claude 판단이지만 가짜 LLM 규칙과 화면 처리 경로를 테스트로 고정). → Task 5 `newRequestOnSampleFormFillsForm`.
2. **다듬기 [적용] 후 사용자가 직접 넣은 버튼·아이템리스트·이미지가 그대로인가** → Task 5 `polishApplyKeepsButtonsAndItemList`.
3. **오래된 선택지 버튼 클릭** — 최근 질문의 선택지만 눌려야 한다 → Task 5 `oldChoicesDisabled`.
4. **질문만 계속 반환되는 경우** — 3번 연속 질문 뒤 요청에는 강제 작성 지시가 붙어야 한다 → Task 3 `forcesDraftAfterThreeQuestions`.
5. **처리 중 중복 전송** — 응답 대기 중에는 보내기·선택지·시작 버튼이 막혀야 한다 → Task 5 `inputLockedWhileWaiting`.

---

### Task 1: 여러 턴 대화를 Claude에 보내기

**Files:**
- Create: `src/main/java/com/alimtalk/llm/LlmTurn.java`
- Modify: `src/main/java/com/alimtalk/llm/LlmClient.java`, `ClaudeLlmClient.java`, `FakeLlmClient.java`
- Test: `src/test/java/com/alimtalk/llm/ClaudeLlmClientTest.java`

**Interfaces:**
- Produces:
  - `record LlmTurn(Role role, String text)` with `enum Role { USER, ASSISTANT, SYSTEM }`; static helpers `user(String)`, `assistant(String)`, `system(String)`
  - `LlmClient`: `LlmResponse generate(String system, List<LlmTurn> turns, Duration timeout) throws LlmException` (추상). 기존 `generate(String system, String user, Duration timeout)`는 `default`로 남기고 `List.of(LlmTurn.user(user))`로 위임.
  - `ClaudeLlmClient`: package-private `StructuredMessageCreateParams<LlmOutput> buildParams(String system, List<LlmTurn> turns)` — USER→`addUserMessage`, ASSISTANT→`addAssistantMessage`, SYSTEM→`addMessage(SystemMessageParam)`(대화 중 시스템 메시지; 반드시 user 턴 뒤 마지막에 온다).

- [ ] **Step 1: 실패 테스트** — `ClaudeLlmClientTest.buildParamsKeepsTurnOrderAndRoles`: 턴 `[user "A", assistant "B", user "C", system "D"]` → `buildParams(...).rawParams().messages()` 크기 4, 역할 순서 user/assistant/user/system(SDK 타입 이름은 컴파일러로 확인), 시스템 프롬프트에 `cache_control` 유지.
- [ ] **Step 2: `./gradlew test --tests '*ClaudeLlmClientTest'` → FAIL(컴파일)**
- [ ] **Step 3: 구현.** `FakeLlmClient`는 새 추상 메서드를 구현하되 이 Task에서는 마지막 user 턴 텍스트로 기존 규칙을 그대로 적용한다.
- [ ] **Step 4: `./gradlew test` → 전체 PASS**
- [ ] **Step 5: 커밋 승인 요청 후 커밋** (`feat: Claude 호출을 여러 턴 대화로 확장`)

---

### Task 2: 응답 형식·생성 지침·프롬프트를 대화형으로

**Files:**
- Modify: `src/main/java/com/alimtalk/llm/LlmOutput.java`, `PromptBuilder.java`
- Modify: `src/main/resources/rules/alimtalk-review-guide.md` (4장 작성 규칙)
- Test: `LlmOutputTest.java`, `PromptBuilderTest.java`

**Interfaces:**
- Produces:
  - `LlmOutput(Status status, Mode mode, String message, List<String> choices, DraftOut draft)`; `enum Status { OK, ASK, AD_CONTENT }`(UNCLEAR 제거), `enum Mode { NEW, POLISH }`. `message`: ASK=질문, OK=한 줄 요약/가정, AD_CONTENT=거절 이유. `choices`: ASK·OK의 후속 제안(최대 6).
  - `PromptBuilder.formContext(TemplateDraft form): String` — "현재 폼:" 블록(카테고리·이름·코드·유형·강조 제목/보조·본문·부가정보·보안·예시값). 빈 폼이면 "현재 폼: 비어 있음".
  - `PromptBuilder.FORCE_DRAFT: String` — "질문을 3번 했으니 이번에는 질문하지 말고 지금까지의 정보로 작성하라."(상수)
  - 시스템 프롬프트 = 기존 지침 + 형식 목표 + 카테고리 + **"## 7. 대화 규칙"**(아래 지침 수정과 같은 내용 요약은 지침 파일에 두고 PromptBuilder는 그대로 이어 붙임).
- 생성 지침 4장 수정(문구는 빌더 재량, 아래 의미 고정):
  - 애매하면 가정 작성 대신 `ASK`: 질문 한 문장 + 버튼으로 쓸 선택지 최대 6개(각 20자 이내). 정보가 충분하면 바로 `OK`.
  - 현재 폼이 함께 온다. 새 메시지 요청이면 `mode=NEW`, 지금 내용을 고치라는 요청이면 `mode=POLISH`. POLISH는 의미·변수를 바꾸지 않고 형식·문장만 개선하며 `message`에 변경 요약 한 줄.
  - 작성(`OK`) 후 `choices`로 후속 제안 가능(예: "고객센터 안내 추가", "이대로 완료").
  - 기존 "가장 일반적인 경우로 가정" 문단 삭제.

- [ ] **Step 1: 실패 테스트** — `LlmOutputTest.statusAndTypesAreEnumsWithAllowedValuesOnly` 기대값을 `OK, ASK, AD_CONTENT` / `NEW, POLISH`(mode)로 변경, `sdkBuildsStructuredOutputSchemaFromRecord` 유지. `PromptBuilderTest.formContextListsTextFields`(본문·예시값 포함, 버튼 언급 없음), `formContextForEmptyForm`, `systemContainsConversationRules`("ASK" 포함, "가장 일반적인 경우로 작성" 미포함), `systemIsStableAcrossCalls` 유지.
- [ ] **Step 2: `./gradlew test --tests '*LlmOutputTest' --tests '*PromptBuilderTest'` → FAIL**
- [ ] **Step 3: 구현.** 다른 곳의 `LlmOutput` 생성자·`UNCLEAR` 참조는 컴파일 오류를 따라 수정(서비스 매핑은 Task 3에서 테스트로 고정하므로 여기서는 컴파일만 맞춘다).
- [ ] **Step 4: `./gradlew test` → 전체 PASS** (기존 서비스 테스트 중 `UNCLEAR`/B 가정 관련은 Task 3에서 바뀌므로, 여기서 깨지면 Task 3 기대에 맞춰 함께 수정)
- [ ] **Step 5: 커밋 승인 요청 후 커밋** (`feat: 응답 형식과 생성 지침을 대화형(질문·선택지·모드)으로 변경`)

---

### Task 3: 생성 서비스를 대화 턴 기반으로 확장 (+ 기존 /generate 호환)

**Files:**
- Modify: `src/main/java/com/alimtalk/template/TemplateGenerationService.java`, `GenerationResult.java`, `TemplateController.java`(새 변형 처리)
- Modify: `src/main/java/com/alimtalk/usage/UsageRecord.java`, `CsvUsageRecorder.java`, `src/main/resources/application.yml`(`path: logs/llm-usage-v2.csv`)
- Create: `src/main/java/com/alimtalk/template/ChatTurnInput.java`
- Test: `TemplateGenerationServiceTest.java`, `TemplateControllerTest.java`, `CsvUsageRecorderTest.java`

**Interfaces:**
- Consumes: Task 1 `LlmTurn`, Task 2 `LlmOutput`/`PromptBuilder.formContext`/`FORCE_DRAFT`
- Produces:
  - `record ChatTurnInput(String role /* user|assistant */, String text, String kind /* assistant일 때 ASK|DRAFT|REJECTED, 없으면 null */)`
  - `GenerationResult` 변형: `Success(draft, warnings, attempts, mode, summary, choices)`, `RuleViolation(draft, errors, warnings, attempts, mode, summary, choices)`, `Rejected(reason, message)`, **`Asked(question, choices)`** (신규). `mode`는 `"NEW"|"POLISH"`.
  - `GenerationResult chat(List<ChatTurnInput> messages, TemplateDraft form, String channelName) throws LlmException`
  - 기존 `GenerationResult generate(String prompt, String channelName)`: 내부적으로 단일 턴 + 빈 폼으로 같은 루프를 쓰고, `Asked`면 `Rejected("UNCLEAR", question)`으로 바꿔 반환(컨트롤러 계약 유지).
  - `UsageRecord`에 `String mode`, `int turns` 추가(끝). CSV 열 끝에 `모드`, `대화턴수`.

동작(스펙 5·6장):
- Claude 턴 구성: 채널 줄 + `formContext(form)`을 **첫 user 턴 앞**에 붙인 user 턴으로 시작, 이후 messages 순서대로 user/assistant. 마지막 assistant 이후 연속 `kind=ASK` assistant가 3개 이상이면 마지막에 `LlmTurn.system(FORCE_DRAFT)` 추가.
- 재생성: 규칙 위반 시 기존처럼 위반 내용을 user 턴으로 덧붙여 재요청(최대 2회, 시간 예산 동일).
- `mode=POLISH`이고 폼 유형이 범위 밖(AD/MI 또는 IMAGE/ITEM_LIST)이면: 검증은 Claude 초안 그대로 하고, 반환 초안의 `msgType`/`emph`는 폼 값으로 되돌린다.
- 사용량 기록: 결과명 `ASK`·`SUCCESS`·`RULE_VIOLATION`·`AD_CONTENT`·`ERROR_*`, `prompt`=마지막 user 발화, `mode`=초안일 때만, `turns`=messages 개수.

- [ ] **Step 1: 실패 테스트** (가짜 LLM은 `ScriptedLlm`이 받은 턴 목록을 기록하도록 확장)
  - `askReturnsQuestionAndChoices`: Claude가 ASK(question "어떤 정보를 넣을까요?", choices 3개) → `Asked`
  - `turnsSentInOrderWithFormContextFirst`: messages `[user "배송 시작 안내 메시지", assistant(ASK) "무엇을…", user "택배사·송장번호"]` → 보낸 턴 3개(USER, ASSISTANT, USER). 첫 USER 텍스트는 채널 줄·"현재 폼" 블록 뒤에 "배송 시작 안내 메시지"를 포함하고, 마지막 USER는 "택배사·송장번호"
  - `forcesDraftAfterThreeQuestions`: assistant(ASK) 3개 연속 뒤 → 마지막 턴이 SYSTEM이고 `FORCE_DRAFT`
  - `noForceAfterTwoQuestions`
  - `polishKeepsOutOfScopeTypesFromForm`: 폼 `AD`/`ITEM_LIST`, Claude 초안 `BA`/`NONE`(POLISH) → 결과 초안 `AD`/`ITEM_LIST`, 본문은 Claude 것
  - `successCarriesModeSummaryChoices`
  - `generateMapsAskToUnclear`: `/generate` 경로에서 ASK → `Rejected("UNCLEAR", 질문)`
  - `recordsAskTurnWithModeBlankAndTurnCount`, `recordsPolishMode`
  - 기존 `assumptionMessage*` 테스트는 B 동작 제거에 맞게 삭제(요약 문구는 `summary`로 이동했음을 `successCarriesModeSummaryChoices`가 대신 확인)
  - `CsvUsageRecorderTest`: 헤더 끝 `모드,대화턴수`, 줄 끝 `,NEW,3`
  - `TemplateControllerTest`: 기존 `/generate` 테스트의 `Success`/`RuleViolation` 생성자 갱신, `askIsUnclear422` 추가
- [ ] **Step 2: `./gradlew test` → FAIL**
- [ ] **Step 3: 구현**
- [ ] **Step 4: `./gradlew test` → 전체 PASS**
- [ ] **Step 5: 커밋 승인 요청 후 커밋** (`feat: 생성 서비스를 대화 턴·다듬기 기반으로 확장`)

---

### Task 4: `POST /api/templates/chat`

**Files:**
- Create: `src/main/java/com/alimtalk/template/ChatController.java`, `ChatRequest.java`, `Channels.java`
- Modify: `TemplateController.java`(채널 표 → `Channels` 사용)
- Test: `src/test/java/com/alimtalk/template/ChatControllerTest.java`

**Interfaces:**
- Consumes: Task 3 `chat(...)`, `GenerationResult`, `ChatTurnInput`
- Produces:
  - `Channels.displayName(String code): String` (미등록·null → `""`), 표: `@mystore→아디다스`, `@hotvpn→PMK`, `@testchannel→테스트채널`
  - 요청 `ChatRequest(@Size(min=1,max=20) @Valid List<Msg> messages, @Size(max=50) String channel, TemplateDraft form)`; `Msg(@Pattern("user|assistant") role, @NotBlank @Size(max=500) text, String kind)`. 마지막이 user가 아니면 400 `BAD_REQUEST`. `form` null이면 빈 초안으로.
  - 응답 200: `{kind:"ASK"|"DRAFT"|"REJECTED", mode, reply:{text, choices}, draft, warnings, errors, attempts}` — Asked→ASK(reply.text=question), Success→DRAFT(errors=[]), RuleViolation→DRAFT(errors 채움), Rejected→REJECTED(reply.text=message).

- [ ] **Step 1: 실패 테스트** — `askResponse`, `draftResponseWithModeAndChoices`, `ruleViolationIsDraftWithErrors200`, `rejectedResponse`, `emptyMessagesIs400`, `twentyOneMessagesIs400`, `tooLongTextIs400`, `lastMessageMustBeUser400`, `badRoleIs400`, `nullFormTreatedAsEmpty`(서비스에 빈 초안 전달), `unknownChannelBlank`, `timeoutIs504`(기존 핸들러 재사용 확인). 서비스는 목.
- [ ] **Step 2: `./gradlew test --tests '*ChatControllerTest'` → FAIL**
- [ ] **Step 3: 구현**
- [ ] **Step 4: `./gradlew test` → 전체 PASS**
- [ ] **Step 5: 커밋 승인 요청 후 커밋** (`feat: 대화형 세팅 API 추가`)

---

### Task 5: 화면 — 대화 카드 (+ 가짜 LLM 대화 규칙, 화면 점검)

**담당 [사용자 결정]:** Task 1~4가 끝난 뒤 세션을 다시 시작하고 진행한다. 가짜 LLM 대화 규칙(`FakeLlmClient`, 서버 코드)은 메인 세션이 먼저 반영한다 → **test 에이전트**가 `verify.html` 점검 항목을 먼저 작성해 FAIL 확인 → **frontend-developer 에이전트**가 `index.html` 구현 → **test 에이전트**가 전체 점검·스크린샷 확인. 에이전트는 커밋하지 않는다.

**Files:**
- Modify: `src/main/resources/static/index.html`(자연어 카드 → 대화 카드)
- Modify: `src/main/java/com/alimtalk/llm/FakeLlmClient.java`(대화 규칙)
- Modify: `src/test/ui/verify.html`(자연어 카드 점검 항목을 대화 카드 기준으로 교체·추가)

**Interfaces:**
- Consumes: Task 4 API
- 가짜 LLM 규칙(마지막 user 턴 기준, 기존 "광고"→거절·"위반"→1,100자 유지): "다듬" 포함 → OK/POLISH(현재 폼 본문을 "▶ " 항목 형태로 바꾼 초안, message "항목별로 정리했습니다.", choices ["더 짧게"]); 대화의 user 턴이 1개이고 "안내 메시지"로 끝남 → ASK("어떤 정보를 넣을까요?", ["택배사·송장번호","배송 예정일","상품명"]); 그 외 → OK/NEW 주문완료 초안(choices ["고객센터 안내 추가","이대로 완료"]).

화면 동작(스펙 5.1~5.3):
- 시작 버튼(고정, Claude 호출 없음): 주문 완료·주문 취소·배송 시작·예약 확정·회원가입·인증번호 → 발화 "<이름> 안내 메시지"; [지금 폼 다듬기] → 발화 "지금 폼을 다듬어줘"(본문 비면 비활성).
- 발화 목록(사용자/시스템 말풍선), 최신 응답의 선택지만 활성, 직접 입력 + [보내기](Enter), [새 대화](기록 초기화, 시작 버튼 다시 표시), 처리 중 "작성 중…" + 입력·버튼 잠금, 기존 범위 안내(`#nlScope`) 유지.
- 매 요청: `{messages, channel, form}`(form은 현재 폼 텍스트 항목). assistant 발화는 응답 `kind`와 함께 보관.
- DRAFT·NEW: 기존 `applyDraft`(덮어쓰기 확인, 버튼·아이템리스트·이미지 비움) + 경고·위반 표시(위반 시 [그래도 폼에 채우기]/[다시 생성]).
- DRAFT·POLISH: 폼에 바로 넣지 않고 말풍선에 요약 + 바뀐 본문 미리보기 + [적용]/[취소](위반이면 [그래도 적용]/[다시 다듬기]). [적용]은 텍스트 항목·예시값만 바꾸고 버튼·아이템리스트·이미지·채널 유지.
- 400/502/504·네트워크 오류는 시스템 말풍선으로 메시지 표시, 대화는 유지.
- 기존 자연어 카드 요소(`#nlPrompt`, `#nlGenerate` 등)와 `/generate` 호출 코드 제거.

- [ ] **Step 1: 실패 점검 항목 추가(verify.html)** — `startButtonsShown`, `startButtonAsksWithChoices`(배송 시작 클릭 → 질문·선택지 3개), `choiceClickFillsForm`(선택지 클릭 → 폼 본문 "[주문완료 안내]"…), `newRequestOnSampleFormFillsForm`(첫 화면에서 "배송 안내 만들어줘" 직접 입력 → NEW로 폼 채움), `oldChoicesDisabled`, `inputLockedWhileWaiting`, `polishShowsApplyCancel`, `polishCancelKeepsForm`, `polishApplyKeepsButtonsAndItemList`(버튼 2개·아이템리스트 헤더 입력 후 다듬기 적용 → 유지), `newChatResets`, `adRejectedShownInChat`, `errorShownInChat`(빈 입력 보내기 불가 또는 400 표시). 기존 nl* 항목은 대체·삭제.
- [ ] **Step 2: 가짜 LLM 서버(8081) + `src/test/ui/verify.sh` → 새 항목 FAIL 확인**
- [ ] **Step 3: 가짜 LLM 규칙 + 화면 구현**
- [ ] **Step 4: `./gradlew test` PASS + 서버 재시작 후 `verify.sh` 전체 PASS + 스크린샷 1장 확인**
- [ ] **Step 5: 커밋 승인 요청 후 커밋** (`feat: 대화형 세팅 화면`)

---

### Task 6: 문서 + 실제 Claude 확인

**Files:**
- Modify: `docs/run-guide.md`(대화형 사용법, 대화 시나리오 테스트 케이스, 사용량 파일 경로 v2), `docs/handoff.md`

- [ ] **Step 1: 문서 갱신**
- [ ] **Step 2: 사용자 승인(비용) 후, 사용자가 키를 넣은 8080 서버로 대화 시나리오 실제 호출 확인** — 시나리오(예): ① [배송 시작] → 질문·선택지 → 선택 → 작성, ② "회원가입 인증번호 보내는 거" → 바로 작성, ③ 예시 폼에서 "더 정중하게" → POLISH 결과 [적용], ④ "할인 행사 홍보" → 거절, ⑤ 질문 3회 유도 후 강제 작성. 결과·토큰·비용을 `logs/llm-usage-v2.csv`로 정리
- [ ] **Step 3: 결과 보고, 커밋 승인 요청 후 커밋** (`docs: 대화형 세팅 실행 가이드·인수인계 갱신`)
