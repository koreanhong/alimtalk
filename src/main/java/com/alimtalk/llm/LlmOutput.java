package com.alimtalk.llm;

import com.alimtalk.template.TemplateDraft;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Claude 구조화 출력 형식. 스키마에서 Map을 피하려고 예시값은 배열로 받는다. */
public record LlmOutput(
        @JsonPropertyDescription("OK: 템플릿 작성, AD_CONTENT: 광고성 요청이라 작성 불가, UNCLEAR: 요청이 불명확함")
        String status,
        @JsonPropertyDescription("status가 OK가 아닐 때 사용자에게 보여줄 한국어 한 문장. OK면 빈 문자열")
        String message,
        @JsonPropertyDescription("템플릿 초안. status가 OK가 아니면 모든 문자열을 빈 문자열로 채운다")
        DraftOut draft) {

    public record DraftOut(
            @JsonPropertyDescription("카테고리 코드") String category,
            @JsonPropertyDescription("템플릿 이름") String name,
            @JsonPropertyDescription("템플릿 코드 (영문·숫자·_)") String code,
            @JsonPropertyDescription("메시지 유형: BA 또는 EX") String msgType,
            @JsonPropertyDescription("강조 유형: NONE 또는 TEXT") String emph,
            @JsonPropertyDescription("강조 제목. emph가 TEXT일 때만, 아니면 빈 문자열") String title,
            @JsonPropertyDescription("강조 보조 문구. 변수 금지. emph가 TEXT일 때만, 아니면 빈 문자열") String sub,
            @JsonPropertyDescription("본문. 변수는 #{변수명}") String content,
            @JsonPropertyDescription("부가정보. 변수 금지. msgType이 EX일 때만, 아니면 빈 문자열") String extra,
            @JsonPropertyDescription("보안 템플릿 여부 (인증번호 등)") boolean security,
            @JsonPropertyDescription("본문·강조 제목에 쓴 모든 변수의 예시값") List<Sample> samples) {

        public TemplateDraft toDraft() {
            Map<String, String> map = new LinkedHashMap<>();
            if (samples != null) {
                for (Sample s : samples) {
                    if (s != null && s.name() != null) {
                        map.put(s.name(), s.value());
                    }
                }
            }
            return new TemplateDraft(category, name, code, msgType, emph, title, sub, content, extra, security, map);
        }
    }

    public record Sample(
            @JsonPropertyDescription("변수 이름 (#{ } 없이)") String name,
            @JsonPropertyDescription("예시값") String value) {}
}
