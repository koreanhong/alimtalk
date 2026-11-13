package com.alimtalk.template;

import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 템플릿 폼 값. 필드 이름은 화면(index.html)의 state 객체와 같다.
 * 크기 제한은 대화형 세팅 요청의 현재 폼(@Valid)에만 적용된다. Claude에 그대로 전달되므로 비용 폭주를 막는 상한이며,
 * 규칙 한도(본문 1,000자 등)를 넘긴 폼도 다듬을 수 있게 규칙보다 넉넉하게 둔다.
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
            @Size(max = 100, message = "폼의 예시값이 너무 깁니다(100자 이내).") String> samples) {}
