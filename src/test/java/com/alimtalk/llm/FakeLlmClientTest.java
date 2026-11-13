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

    @Test
    void oneShotGenerateStillWorks() throws Exception {
        LlmOutput out = fake.generate("sys", "요청: 주문 관련 메시지 세팅해줘", Duration.ofSeconds(1)).output();

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
    }
}
