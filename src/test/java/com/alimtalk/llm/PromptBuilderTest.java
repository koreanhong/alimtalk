package com.alimtalk.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.alimtalk.rules.RuleLoader;
import com.alimtalk.template.TemplateDraft;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder(new RuleLoader());

    @Test
    void systemContainsReviewGuideAndTargets() {
        String system = builder.system();

        assertThat(system).contains("# 알림톡 템플릿 작성 지침");
        assertThat(system).contains("700");
        assertThat(system).contains("002001");
        assertThat(system).doesNotContain("1000").doesNotContain("1,000");
    }

    @Test
    void systemIsStableAcrossCalls() {
        assertThat(builder.system()).isEqualTo(builder.system());
    }

    @Test
    void formContextListsTextFields() {
        TemplateDraft form = new TemplateDraft("003001", "배송 시작 안내", "SHIP_START_01", "EX", "TEXT",
                "#{송장번호}", "배송 시작", "#{고객명}님, 배송이 시작되었습니다.", "문의: 고객센터", false,
                Map.of("고객명", "홍길동", "송장번호", "1234"));

        String ctx = builder.formContext(form);

        assertThat(ctx).startsWith("현재 폼:");
        assertThat(ctx).contains("003001", "배송 시작 안내", "EX", "TEXT", "#{고객명}님, 배송이 시작되었습니다.",
                "문의: 고객센터", "고객명=홍길동");
        assertThat(ctx).doesNotContain("버튼");
    }

    @Test
    void formContextForEmptyForm() {
        TemplateDraft empty = new TemplateDraft("", "", "", "", "", "", "", "", "", false, Map.of());

        assertThat(builder.formContext(empty)).isEqualTo("현재 폼: 비어 있음");
        assertThat(builder.formContext(null)).isEqualTo("현재 폼: 비어 있음");
    }

    @Test
    void systemContainsConversationRules() {
        String system = builder.system();

        assertThat(system).contains("ASK").contains("POLISH").contains("choices");
        assertThat(system).doesNotContain("가장 일반적인 경우로 작성");
    }

    @Test
    void forceDraftInstructionForbidsFurtherQuestions() {
        assertThat(PromptBuilder.FORCE_DRAFT).contains("질문하지 말고");
    }

    @Test
    void userOmitsChannelLineWhenBlank() {
        assertThat(builder.user("주문 관련 메시지 세팅해줘", "")).doesNotContain("발신 채널");
        assertThat(builder.user("주문 관련 메시지 세팅해줘", "마이스토어")).contains("발신 채널: 마이스토어");
    }

    @Test
    void retryIncludesErrorsAndPrevious() {
        TemplateDraft previous = new TemplateDraft("002001", "주문 완료 안내", "ORDER_COMPLETE_01", "BA", "NONE",
                "", "", "#{고객명}님, 이전 본문입니다.", "", false, Map.of("고객명", "홍길동"));
        List<String> errors = List.of("본문이 1,050자입니다 (최대 1,000자). 50자 이상 줄여야 합니다.", "부가정보에는 변수를 쓸 수 없습니다.");

        String retry = builder.retry("주문 관련 메시지 세팅해줘", "마이스토어", previous, errors);

        assertThat(retry).contains("주문 관련 메시지 세팅해줘");
        assertThat(retry).contains("#{고객명}님, 이전 본문입니다.");
        assertThat(retry).contains(errors.get(0)).contains(errors.get(1));
    }
}
