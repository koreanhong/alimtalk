package com.alimtalk.template;

import java.util.List;

public sealed interface GenerationResult {

    /** 오류 없이 작성됨. mode: NEW | POLISH, summary: Claude가 남긴 한 줄 요약, choices: 후속 제안. */
    record Success(TemplateDraft draft, List<String> warnings, int attempts,
                   String mode, String summary, List<String> choices) implements GenerationResult {

        public Success(TemplateDraft draft, List<String> warnings, int attempts) {
            this(draft, warnings, attempts, "NEW", "", List.of());
        }
    }

    /** LLM이 생성을 거절함 (reason: AD_CONTENT | UNCLEAR). */
    record Rejected(String reason, String message) implements GenerationResult {}

    /** 재생성 후에도 오류 수준 위반이 남음. draft는 마지막 결과. */
    record RuleViolation(TemplateDraft draft, List<String> errors, List<String> warnings, int attempts,
                         String mode, String summary, List<String> choices) implements GenerationResult {

        public RuleViolation(TemplateDraft draft, List<String> errors, List<String> warnings, int attempts) {
            this(draft, errors, warnings, attempts, "NEW", "", List.of());
        }
    }

    /** 작성에 필요한 정보를 묻는 질문(대화형). */
    record Asked(String question, List<String> choices) implements GenerationResult {}
}
