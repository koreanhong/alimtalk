package com.alimtalk.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.alimtalk.template.TemplateDraft;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleValidatorTest {

    private final RuleValidator validator = new RuleValidator(new RuleLoader().ruleSet());

    /** 유효한 기본 draft: 기본형, 강조 없음, 짧은 본문, 변수 1개 + 예시값. */
    private static TemplateDraft base() {
        return new TemplateDraft("002001", "주문 완료 안내", "ORDER_COMPLETE_01", "BA", "NONE",
                "", "", "#{고객명}님, 주문이 접수되었습니다.", "", false, Map.of("고객명", "홍길동"));
    }

    private static TemplateDraft with(TemplateDraft d, String field, Object value) {
        return new TemplateDraft(
                field.equals("category") ? (String) value : d.category(),
                field.equals("name") ? (String) value : d.name(),
                field.equals("code") ? (String) value : d.code(),
                field.equals("msgType") ? (String) value : d.msgType(),
                field.equals("emph") ? (String) value : d.emph(),
                field.equals("title") ? (String) value : d.title(),
                field.equals("sub") ? (String) value : d.sub(),
                field.equals("content") ? (String) value : d.content(),
                field.equals("extra") ? (String) value : d.extra(),
                field.equals("security") ? (Boolean) value : d.security(),
                field.equals("samples") ? castSamples(value) : d.samples());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castSamples(Object value) {
        return (Map<String, String>) value;
    }

    @Test
    void validDraftHasNoErrors() {
        ValidationResult r = validator.validate(base());
        assertThat(r.errors()).isEmpty();
        assertThat(r.hasErrors()).isFalse();
    }

    @Test
    void contentLengthCountsSampleValues() {
        String content = "가".repeat(900) + "#{상품명}";
        TemplateDraft d = with(with(base(), "content", content), "samples", Map.of("상품명", "나".repeat(200)));

        ValidationResult r = validator.validate(d);

        assertThat(r.errors()).anyMatch(e -> e.contains("최대 1,000자"));
    }

    @Test
    void extraRequiredForEx() {
        ValidationResult r = validator.validate(with(with(base(), "msgType", "EX"), "extra", ""));
        assertThat(r.errors()).anyMatch(e -> e.contains("부가정보"));
    }

    @Test
    void extraMustNotContainVariables() {
        TemplateDraft d = with(with(base(), "msgType", "EX"), "extra", "문의: #{전화번호}");
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("부가정보") && e.contains("변수"));
    }

    @Test
    void subMustNotContainVariables() {
        TemplateDraft d = with(with(with(base(), "emph", "TEXT"), "title", "결제 완료"), "sub", "#{고객명}님");
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("보조 문구") && e.contains("변수"));
    }

    @Test
    void emphasisTextRequiresTitleAndSub() {
        TemplateDraft d = with(with(with(base(), "emph", "TEXT"), "title", "결제 완료"), "sub", "");
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("보조 문구"));
    }

    @Test
    void variableCountOver30IsError() {
        StringBuilder content = new StringBuilder("안내드립니다. ");
        Map<String, String> samples = new LinkedHashMap<>();
        for (int i = 1; i <= 31; i++) {
            content.append("#{v").append(i).append("} ");
            samples.put("v" + i, "x");
        }
        TemplateDraft d = with(with(base(), "content", content.toString()), "samples", samples);

        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("최대 30개"));
    }

    @Test
    void missingSampleIsError() {
        TemplateDraft missing = with(base(), "samples", Map.of());
        Map<String, String> blank = new HashMap<>();
        blank.put("고객명", " ");
        TemplateDraft blankValue = with(base(), "samples", blank);

        assertThat(validator.validate(missing).errors()).anyMatch(e -> e.contains("예시값") && e.contains("#{고객명}"));
        assertThat(validator.validate(blankValue).errors()).anyMatch(e -> e.contains("예시값"));
    }

    @Test
    void emptyVariableNameIsError() {
        TemplateDraft d = with(base(), "content", "주문이 접수되었습니다. #{}");
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("빈 변수"));
    }

    @Test
    void variablesOnlyContentIsError() {
        TemplateDraft d = with(with(base(), "content", "#{a} #{b}"), "samples", Map.of("a", "1", "b", "2"));
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("변수로만"));
    }

    @Test
    void invalidCodeIsError() {
        assertThat(validator.validate(with(base(), "code", "주문-01")).errors()).anyMatch(e -> e.contains("템플릿 코드"));
    }

    @Test
    void unknownCategoryIsError() {
        assertThat(validator.validate(with(base(), "category", "123456")).errors()).anyMatch(e -> e.contains("카테고리"));
    }

    @Test
    void unknownMsgTypeIsError() {
        assertThat(validator.validate(with(base(), "msgType", "XX")).errors()).anyMatch(e -> e.contains("메시지 유형"));
    }

    @Test
    void unknownEmphIsError() {
        assertThat(validator.validate(with(base(), "emph", "XX")).errors()).anyMatch(e -> e.contains("강조 유형"));
    }

    @Test
    void bannedPhraseIsError() {
        TemplateDraft d = with(base(), "content", "#{고객명}님, 지금 구매하시면 좋습니다.");
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("지금 구매"));
    }

    @Test
    void cautionWordIsWarningOnly() {
        TemplateDraft d = with(with(base(), "content", "#{고객명}님, 주문이 접수되었습니다.\n할인 금액: #{할인금액}"),
                "samples", Map.of("고객명", "홍길동", "할인금액", "3,000"));

        ValidationResult r = validator.validate(d);

        assertThat(r.errors()).isEmpty();
        assertThat(r.warnings()).hasSize(1);
    }

    @Test
    void securityWithoutAuthIsWarning() {
        ValidationResult r = validator.validate(with(base(), "security", true));
        assertThat(r.errors()).isEmpty();
        assertThat(r.warnings()).anyMatch(w -> w.contains("보안 템플릿"));
    }

    @Test
    void nullStringFieldsTreatedAsEmpty() {
        TemplateDraft d = new TemplateDraft("002001", "주문 완료 안내", "ORDER_COMPLETE_01", "BA", "NONE",
                null, null, "#{고객명}님, 주문이 접수되었습니다.", null, false, Map.of("고객명", "홍길동"));
        assertThat(validator.validate(d).errors()).isEmpty();
    }

    // ---------- 유형 4종·아이템리스트 (설계 12.4) ----------

    private static TemplateDraft.Row row(String name, String desc) {
        return new TemplateDraft.Row(name, desc);
    }

    /** 유효한 아이템리스트형 draft. */
    private static TemplateDraft itemDraft(TemplateDraft.Item item, Map<String, String> samples) {
        return new TemplateDraft("002001", "주문 완료 안내", "ORDER_COMPLETE_01", "BA", "ITEM_LIST",
                "", "", "주문이 접수되었습니다.", "", false, samples, item);
    }

    private static TemplateDraft.Item item(String header, String hlTitle, String hlDesc, List<TemplateDraft.Row> rows,
                                           String sumName, String sumDesc) {
        return new TemplateDraft.Item(header, hlTitle, hlDesc, rows, sumName, sumDesc);
    }

    private static TemplateDraft.Item validItem() {
        return item("주문 내역", "", "", List.of(row("상품명", "#{상품명}"), row("수량", "1개")), "결제금액", "#{결제금액}원");
    }

    private static final Map<String, String> ITEM_SAMPLES = Map.of("상품명", "무선 키보드", "결제금액", "129,000");

    @Test
    void acceptsAllMessageAndEmphTypes() {
        for (String t : List.of("BA", "AD")) {
            assertThat(validator.validate(with(base(), "msgType", t)).errors()).as(t).isEmpty();
        }
        for (String t : List.of("EX", "MI")) {
            assertThat(validator.validate(with(with(base(), "msgType", t), "extra", "문의: 1588-0000")).errors()).as(t).isEmpty();
        }
        assertThat(validator.validate(with(base(), "emph", "IMAGE")).errors()).isEmpty();
        assertThat(validator.validate(itemDraft(validItem(), ITEM_SAMPLES)).errors()).isEmpty();
    }

    @Test
    void miRequiresExtraWithoutVariables() {
        assertThat(validator.validate(with(base(), "msgType", "MI")).errors()).anyMatch(e -> e.contains("부가정보"));
        TemplateDraft withVar = with(with(base(), "msgType", "MI"), "extra", "문의 #{고객명}");
        assertThat(validator.validate(withVar).errors()).anyMatch(e -> e.contains("부가정보에는 변수"));
    }

    @Test
    void imageTypeIgnoresTitleAndSub() {
        TemplateDraft d = with(with(with(base(), "emph", "IMAGE"), "title", "가".repeat(80)), "sub", "#{x}");
        assertThat(validator.validate(d).errors()).isEmpty();
    }

    @Test
    void itemListNeedsItemContent() {
        assertThat(validator.validate(itemDraft(null, ITEM_SAMPLES)).errors()).anyMatch(e -> e.contains("아이템리스트"));
    }

    @Test
    void itemListNeedsHeaderOrHighlight() {
        TemplateDraft none = itemDraft(item("", "", "", validItem().list(), "", ""), ITEM_SAMPLES);
        assertThat(validator.validate(none).errors()).anyMatch(e -> e.contains("헤더 또는 아이템 하이라이트"));
        TemplateDraft hlOnly = itemDraft(item("", "주문 상품", "", validItem().list(), "", ""), ITEM_SAMPLES);
        assertThat(validator.validate(hlOnly).errors()).isEmpty();
    }

    @Test
    void itemRowCount2to10() {
        TemplateDraft one = itemDraft(item("주문 내역", "", "", List.of(row("상품명", "키보드")), "", ""), ITEM_SAMPLES);
        assertThat(validator.validate(one).errors()).anyMatch(e -> e.contains("2~10개") && e.contains("현재 1개"));
        List<TemplateDraft.Row> eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> row("항목" + i, "값")).toList();
        TemplateDraft many = itemDraft(item("주문 내역", "", "", eleven, "", ""), ITEM_SAMPLES);
        assertThat(validator.validate(many).errors()).anyMatch(e -> e.contains("현재 11개"));
        TemplateDraft nullList = itemDraft(item("주문 내역", "", "", null, "", ""), ITEM_SAMPLES);
        assertThat(validator.validate(nullList).errors()).anyMatch(e -> e.contains("현재 0개"));
    }

    @Test
    void itemRowNeedsNameAndDesc() {
        TemplateDraft d = itemDraft(item("주문 내역", "", "", List.of(row("상품명", "키보드"), row("", "1개")), "", ""), ITEM_SAMPLES);
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("2번 항목"));
    }

    @Test
    void itemNameAndSumNameRejectVariables() {
        TemplateDraft d = itemDraft(item("주문 내역", "", "", List.of(row("#{상품명}", "키보드"), row("수량", "1개")),
                "#{결제금액}", "129,000원"), ITEM_SAMPLES);
        List<String> errors = validator.validate(d).errors();
        assertThat(errors).anyMatch(e -> e.contains("항목명에는 변수"));
        assertThat(errors).anyMatch(e -> e.contains("요약 이름에는 변수"));
    }

    @Test
    void summaryNeedsBothNameAndDesc() {
        TemplateDraft d = itemDraft(item("주문 내역", "", "", validItem().list(), "결제금액", ""), ITEM_SAMPLES);
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("이름과 내용을 함께"));
    }

    @Test
    void sumDescMustBeNumericAfterSubstitution() {
        TemplateDraft text = itemDraft(item("주문 내역", "", "", validItem().list(), "결제금액", "무료"), ITEM_SAMPLES);
        assertThat(validator.validate(text).errors()).anyMatch(e -> e.contains("숫자"));
        TemplateDraft badSample = itemDraft(validItem(), Map.of("상품명", "키보드", "결제금액", "십만원"));
        assertThat(validator.validate(badSample).errors()).anyMatch(e -> e.contains("숫자"));
        TemplateDraft dollar = itemDraft(item("주문 내역", "", "", validItem().list(), "합계", "$12.50"), ITEM_SAMPLES);
        assertThat(validator.validate(dollar).errors()).isEmpty();
    }

    @Test
    void itemHeaderAndHighlightLengths() {
        TemplateDraft d = itemDraft(item("가".repeat(17), "나".repeat(31), "다".repeat(20), validItem().list(), "", ""), ITEM_SAMPLES);
        List<String> errors = validator.validate(d).errors();
        assertThat(errors).anyMatch(e -> e.contains("헤더") && e.contains("16자"));
        assertThat(errors).anyMatch(e -> e.contains("하이라이트 제목") && e.contains("30자"));
        assertThat(errors).anyMatch(e -> e.contains("하이라이트 설명") && e.contains("19자"));
    }

    @Test
    void itemListVariablesNeedSamples() {
        TemplateDraft d = itemDraft(item("#{주문번호} 내역", "", "", List.of(row("상품명", "#{상품명}"), row("수량", "#{수량}")),
                "", ""), Map.of("상품명", "키보드"));
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("#{주문번호}") && e.contains("#{수량}"));
    }

    @Test
    void itemListCountsTowardTotalLength() {
        List<TemplateDraft.Row> rows = List.of(row("내용", "가".repeat(600)), row("추가", "나".repeat(500)));
        TemplateDraft d = itemDraft(item("주문 내역", "", "", rows, "", ""), ITEM_SAMPLES);
        assertThat(validator.validate(d).errors()).anyMatch(e -> e.contains("최대 1,000자"));
    }

    @Test
    void itemFieldsIgnoredWhenNotItemList() {
        TemplateDraft d = new TemplateDraft("002001", "주문 완료 안내", "ORDER_COMPLETE_01", "BA", "NONE",
                "", "", "주문이 접수되었습니다.", "", false, Map.of(), item("", "", "", List.of(), "#{x}", "무료"));
        assertThat(validator.validate(d).errors()).isEmpty();
    }
}
