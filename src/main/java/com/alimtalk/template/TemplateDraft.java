package com.alimtalk.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * 템플릿 폼 값. 필드 이름은 화면(index.html)의 state 객체와 같다.
 * 크기 제한은 대화형 세팅 요청의 현재 폼(@Valid)에만 적용된다. Claude에 그대로 전달되므로 비용 폭주를 막는 상한이며,
 * 규칙 한도(본문 1,000자 등)를 넘긴 폼도 다듬을 수 있게 규칙보다 넉넉하게 둔다.
 * item은 아이템리스트형 강조 내용(화면 state.item과 같은 모양). 아이템리스트형이 아니면 null이거나 무시된다.
 */
public record TemplateDraft(
        @Size(max = 200, message = "폼의 카테고리 값이 너무 깁니다(200자 이내).") String category,
        @Size(max = 200, message = "폼의 템플릿 이름이 너무 깁니다(200자 이내).") String name,
        @Size(max = 200, message = "폼의 템플릿 코드가 너무 깁니다(200자 이내).") String code,
        @Size(max = 200, message = "폼의 메시지 유형 값이 너무 깁니다(200자 이내).") String msgType,
        @Size(max = 200, message = "폼의 강조 유형 값이 너무 깁니다(200자 이내).") String emph,
        @Size(max = 200, message = "폼의 강조 제목이 너무 깁니다(200자 이내).") String title,
        @Size(max = 200, message = "폼의 강조 보조 문구가 너무 깁니다(200자 이내).") String sub,
        @Size(max = 2000, message = "폼의 본문이 너무 깁니다(2,000자 이내).") String content,
        @Size(max = 2000, message = "폼의 부가정보가 너무 깁니다(2,000자 이내).") String extra,
        boolean security,
        @Size(max = 60, message = "폼의 예시값은 60개까지 보낼 수 있습니다.")
        Map<@Size(max = 100, message = "폼의 예시값 변수 이름이 너무 깁니다(100자 이내).") String,
            @Size(max = 100, message = "폼의 예시값이 너무 깁니다(100자 이내).") String> samples,
        @Valid Item item) {

    /** 아이템리스트 없이 만드는 기존 형태. */
    public TemplateDraft(String category, String name, String code, String msgType, String emph, String title, String sub,
                         String content, String extra, boolean security, Map<String, String> samples) {
        this(category, name, code, msgType, emph, title, sub, content, extra, security, samples, null);
    }

    /** 아이템리스트형 강조. list는 항목(항목명·내용), sum*는 요약. */
    public record Item(
            @Size(max = 200, message = "폼의 아이템리스트 헤더가 너무 깁니다(200자 이내).") String header,
            @Size(max = 200, message = "폼의 아이템리스트 하이라이트 제목이 너무 깁니다(200자 이내).") String hlTitle,
            @Size(max = 200, message = "폼의 아이템리스트 하이라이트 설명이 너무 깁니다(200자 이내).") String hlDesc,
            @Size(max = 10, message = "폼의 아이템리스트 항목은 10개까지 보낼 수 있습니다.") List<@Valid Row> list,
            @Size(max = 200, message = "폼의 아이템리스트 요약 이름이 너무 깁니다(200자 이내).") String sumName,
            @Size(max = 200, message = "폼의 아이템리스트 요약 내용이 너무 깁니다(200자 이내).") String sumDesc) {}

    public record Row(
            @Size(max = 200, message = "폼의 아이템리스트 항목명이 너무 깁니다(200자 이내).") String name,
            @Size(max = 200, message = "폼의 아이템리스트 항목 내용이 너무 깁니다(200자 이내).") String desc) {}
}
