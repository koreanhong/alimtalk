package com.alimtalk.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 화면 점검(src/test/ui)이 기대하는 가짜 LLM 대화 규칙. 요청 문장은 마지막 user 턴의 "요청: " 뒤를 본다. */
class FakeLlmClientTest {

    private final FakeLlmClient fake = new FakeLlmClient();

    private LlmOutput send(LlmTurn... turns) {
        return fake.generate("sys", List.of(turns), Duration.ofSeconds(1)).output();
    }

    private static LlmTurn first(String request) {
        return LlmTurn.user("발신 채널: 아디다스\n현재 폼: 비어 있음\n\n요청: " + request);
    }

    @Test
    void createRequestAsksTypeWithChoices() {
        LlmOutput out = send(first("생성해줘"));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.ASK);
        assertThat(out.message()).isEqualTo("어떤 유형의 메시지인가요?");
        assertThat(out.choices()).containsExactly("배송 안내", "인증번호", "주문 완료", "예약 확정", "회원가입");
    }

    @Test
    void startButtonFirstTurnAsksWhatToInclude() {
        LlmOutput out = send(first("배송 시작 안내 메시지"));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.ASK);
        assertThat(out.choices()).containsExactly("택배사·송장번호", "배송 예정일", "상품명");
    }

    @Test
    void answerAfterQuestionWritesNewDraft() {
        LlmOutput out = send(first("배송 시작 안내 메시지"), LlmTurn.assistant("어떤 정보를 넣을까요?"), LlmTurn.user("택배사·송장번호"));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
        assertThat(out.mode()).isEqualTo(LlmOutput.Mode.NEW);
        assertThat(out.draft().content()).startsWith("[주문완료 안내]");
        assertThat(out.choices()).containsExactly("고객센터 안내 추가", "이대로 완료");
    }

    @Test
    void sentenceWithEnoughInfoWritesImmediately() {
        LlmOutput out = send(first("배송 안내 만들어줘"));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
        assertThat(out.mode()).isEqualTo(LlmOutput.Mode.NEW);
    }

    @Test
    void polishRequestReturnsPolishDraft() {
        LlmOutput out = send(first("지금 폼을 다듬어줘"));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
        assertThat(out.mode()).isEqualTo(LlmOutput.Mode.POLISH);
        assertThat(out.message()).isEqualTo("항목별로 정리했습니다.");
        assertThat(out.draft().content()).contains("▶");
    }

    @Test
    void adRequestRejected() {
        assertThat(send(first("할인 행사 광고")).status()).isEqualTo(LlmOutput.Status.AD_CONTENT);
    }

    @Test
    void violationKeywordReturnsTooLongDraft() {
        LlmOutput out = send(first("위반 테스트"));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
        assertThat(out.draft().content().length()).isGreaterThan(1000);
    }

    private static final PromptBuilder PROMPTS = new PromptBuilder(new com.alimtalk.rules.RuleLoader());

    /** 서버(TemplateGenerationService)가 만드는 것과 같은 첫 발화: 채널 + 현재 폼 + 요청. */
    private static LlmTurn withForm(com.alimtalk.template.TemplateDraft form, String request) {
        return LlmTurn.user(PROMPTS.channelLine("아디다스") + PROMPTS.formContext(form) + "\n\n요청: " + request);
    }

    private static com.alimtalk.template.TemplateDraft form(String msgType, String emph) {
        return new com.alimtalk.template.TemplateDraft("003001", "배송 시작 안내", "SHIP_START_01", msgType, emph,
                "#{송장번호}", "배송 시작", "#{고객명}님, 배송이 시작되었습니다.\n- 택배사: #{택배사}", "문의: 고객센터 1588-0000", false,
                new java.util.LinkedHashMap<>(java.util.Map.of("고객명", "홍길동", "택배사", "CJ대한통운", "송장번호", "1234")));
    }

    @Test
    void polishWithViolationReturnsTooLongPolishDraft() {
        LlmOutput out = send(withForm(form("BA", "NONE"), "다듬어줘 위반"));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
        assertThat(out.mode()).isEqualTo(LlmOutput.Mode.POLISH);
        assertThat(out.draft().content().length()).isGreaterThan(1000);
    }

    @Test
    void warningKeywordReturnsDraftWithCautionWord() {
        LlmOutput out = send(first("경고 테스트"));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
        assertThat(out.mode()).isEqualTo(LlmOutput.Mode.NEW);
        com.alimtalk.rules.RuleValidator validator = new com.alimtalk.rules.RuleValidator(new com.alimtalk.rules.RuleLoader().ruleSet());
        com.alimtalk.rules.ValidationResult result = validator.validate(out.draft().toDraft());
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void polishKeepsFormTypesAndExtra() {
        LlmOutput out = send(withForm(form("MI", "NONE"), "다듬어줘"));

        assertThat(out.mode()).isEqualTo(LlmOutput.Mode.POLISH);
        assertThat(out.draft().content()).isEqualTo("[다듬음] #{고객명}님, 배송이 시작되었습니다.\n- 택배사: #{택배사}");
        assertThat(out.draft().msgType()).isEqualTo(LlmOutput.MsgType.MI);
        assertThat(out.draft().extra()).isEqualTo("문의: 고객센터 1588-0000");
        assertThat(out.draft().name()).isEqualTo("배송 시작 안내");
        assertThat(out.draft().toDraft().samples()).containsEntry("고객명", "홍길동").containsEntry("택배사", "CJ대한통운");
    }

    @Test
    void polishKeepsChannelAddAndImageTypes() {
        LlmOutput out = send(withForm(form("AD", "IMAGE"), "다듬어줘"));

        assertThat(out.draft().msgType()).isEqualTo(LlmOutput.MsgType.AD);
        assertThat(out.draft().emph()).isEqualTo(LlmOutput.Emph.IMAGE);
        assertThat(out.draft().extra()).isEmpty();
        assertThat(VALIDATOR.validate(out.draft().toDraft()).errors()).isEmpty();
    }

    @Test
    void polishKeepsItemList() {
        com.alimtalk.template.TemplateDraft f = new com.alimtalk.template.TemplateDraft("002001", "주문 완료", "C", "BA",
                "ITEM_LIST", "", "", "주문이 접수되었습니다.", "", false, java.util.Map.of("결제금액", "129,000"),
                new com.alimtalk.template.TemplateDraft.Item("주문 내역", "", "",
                        List.of(new com.alimtalk.template.TemplateDraft.Row("상품명", "키보드"),
                                new com.alimtalk.template.TemplateDraft.Row("수량", "1개")),
                        "결제금액", "#{결제금액}원"));

        LlmOutput out = send(withForm(f, "다듬어줘"));

        assertThat(out.draft().emph()).isEqualTo(LlmOutput.Emph.ITEM_LIST);
        assertThat(out.draft().item().header()).isEqualTo("주문 내역");
        assertThat(out.draft().item().list()).extracting(LlmOutput.RowOut::name).containsExactly("상품명", "수량");
        assertThat(out.draft().item().sumDesc()).isEqualTo("#{결제금액}원");
        assertThat(VALIDATOR.validate(out.draft().toDraft()).errors()).isEmpty();
    }

    @Test
    void itemListRequest() {
        LlmOutput out = send(first("아이템리스트로 주문 안내"));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
        assertThat(out.mode()).isEqualTo(LlmOutput.Mode.NEW);
        assertThat(out.draft().emph()).isEqualTo(LlmOutput.Emph.ITEM_LIST);
        assertThat(out.draft().item().list()).hasSize(2);
        assertThat(out.draft().item().sumName()).isNotBlank();
        assertThat(VALIDATOR.validate(out.draft().toDraft()).errors()).isEmpty();
    }

    @Test
    void channelAddRequest() {
        LlmOutput out = send(first("채널 추가 넣은 주문 안내"));

        assertThat(out.draft().msgType()).isEqualTo(LlmOutput.MsgType.AD);
        assertThat(out.draft().extra()).isEmpty();
        assertThat(VALIDATOR.validate(out.draft().toDraft()).errors()).isEmpty();
    }

    @Test
    void imageRequest() {
        LlmOutput out = send(first("이미지형으로 배송 안내"));

        assertThat(out.draft().emph()).isEqualTo(LlmOutput.Emph.IMAGE);
        assertThat(VALIDATOR.validate(out.draft().toDraft()).errors()).isEmpty();
    }

    private static final com.alimtalk.rules.RuleValidator VALIDATOR =
            new com.alimtalk.rules.RuleValidator(new com.alimtalk.rules.RuleLoader().ruleSet());

    @Test
    void polishOfInScopeExtraFormPassesRules() {
        LlmOutput out = send(withForm(form("EX", "TEXT"), "더 정중하게"));

        assertThat(out.draft().msgType()).isEqualTo(LlmOutput.MsgType.EX);
        assertThat(out.draft().emph()).isEqualTo(LlmOutput.Emph.TEXT);
        assertThat(out.draft().extra()).isEqualTo("문의: 고객센터 1588-0000");
        com.alimtalk.rules.RuleValidator validator = new com.alimtalk.rules.RuleValidator(new com.alimtalk.rules.RuleLoader().ruleSet());
        assertThat(validator.validate(out.draft().toDraft()).errors()).isEmpty();
    }

    @Test
    void polishWithoutFormReturnsFixedDraft() {
        LlmOutput out = send(first("지금 폼을 다듬어줘"));

        assertThat(out.draft().content()).startsWith("[주문완료 안내]");
    }

    @Test
    void oneShotGenerateStillWorks() throws Exception {
        LlmOutput out = fake.generate("sys", "요청: 주문 관련 메시지 세팅해줘", Duration.ofSeconds(1)).output();

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
    }
}
