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
    void formContextAsksToKeepExtraForExtraInfoTypes() {
        for (String type : List.of("EX", "MI")) {
            TemplateDraft form = new TemplateDraft("003001", "배송", "C", type, "NONE", "", "", "본문", "문의", false, Map.of());
            assertThat(builder.formContext(form)).contains("부가정보를 비우지 말고");
        }
        TemplateDraft basic = new TemplateDraft("003001", "배송", "C", "BA", "NONE", "", "", "본문", "", false, Map.of());
        assertThat(builder.formContext(basic)).doesNotContain("부가정보를 비우지 말고");
    }

    @Test
    void formContextIncludesSecurityFlag() {
        TemplateDraft secure = new TemplateDraft("004001", "인증", "C", "BA", "NONE", "", "", "인증번호 #{번호}", "", true, Map.of());
        TemplateDraft normal = new TemplateDraft("004001", "인증", "C", "BA", "NONE", "", "", "인증번호 #{번호}", "", false, Map.of());

        assertThat(builder.formContext(secure)).contains("- 보안 템플릿: 예");
        assertThat(builder.formContext(normal)).contains("- 보안 템플릿: 아니오");
    }

    @Test
    void formContextShowsNullFieldsAsBlank() {
        TemplateDraft form = new TemplateDraft(null, "배송", null, null, null, null, null, "본문", null, false, null);

        String ctx = builder.formContext(form);

        assertThat(ctx).doesNotContain("null");
        assertThat(ctx).contains("- 카테고리: \n", "- 본문:\n본문");
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

    // ---------- 유형 4종·아이템리스트 (설계 12장) ----------

    private static TemplateDraft itemForm(String emph) {
        return new TemplateDraft("002001", "주문 완료", "C", "MI", emph, "", "", "주문이 접수되었습니다.", "문의", false,
                Map.of("결제금액", "129,000"),
                new TemplateDraft.Item("주문 내역", "주문 상품", "1건",
                        List.of(new TemplateDraft.Row("상품명", "키보드"), new TemplateDraft.Row("수량", "1개")),
                        "결제금액", "#{결제금액}원"));
    }

    @Test
    void formContextIncludesItemListBeforeContent() {
        String ctx = builder.formContext(itemForm("ITEM_LIST"));

        assertThat(ctx).contains("- 아이템 헤더: 주문 내역\n", "- 아이템 하이라이트 제목: 주문 상품\n",
                "- 아이템 하이라이트 설명: 1건\n", "- 아이템 항목 1: 상품명 | 키보드\n", "- 아이템 항목 2: 수량 | 1개\n",
                "- 아이템 요약: 결제금액 | #{결제금액}원\n");
        assertThat(ctx.indexOf("- 아이템 헤더")).isLessThan(ctx.indexOf("- 본문:"));
    }

    @Test
    void formContextOmitsItemListForOtherEmph() {
        assertThat(builder.formContext(itemForm("NONE"))).doesNotContain("아이템");
    }

    @Test
    void formWithOnlyItemListIsNotEmpty() {
        TemplateDraft onlyItem = new TemplateDraft("", "", "", "BA", "ITEM_LIST", "", "", "", "", false, Map.of(),
                new TemplateDraft.Item("주문 내역", "", "", List.of(), "", ""));
        assertThat(builder.formContext(onlyItem)).isNotEqualTo("현재 폼: 비어 있음");
    }

    @Test
    void systemListsAllTypesAndItemTargets() {
        String system = builder.system();

        assertThat(system).contains("BA, EX, AD, MI").contains("NONE, TEXT, IMAGE, ITEM_LIST");
        assertThat(system).contains("아이템리스트").contains("헤더 11자").contains("2~10개");
        assertThat(system).contains("채널추가형").contains("요청했을 때만");
    }

    @Test
    void formContextAsksToKeepTypes() {
        TemplateDraft form = new TemplateDraft("003001", "배송", "C", "AD", "IMAGE", "", "", "본문", "", false, Map.of());
        assertThat(builder.formContext(form)).contains("※ 현재 메시지 유형 AD, 강조 유형 IMAGE").contains("바꾸라고 하지 않으면 그대로");
    }

    @Test
    void systemTargetsMentionItemListAndPolishKeepsTypes() {
        String system = builder.system();
        assertThat(system).contains("강조 제목·보조 문구·아이템리스트·본문·부가정보를 합친 길이");
        assertThat(system).contains("새로 만들 때 기준").contains("다듬기에서는 현재 폼의 유형을 유지");
        assertThat(system).contains("예시값은 숫자");
    }
}
