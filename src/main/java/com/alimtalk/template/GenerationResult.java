package com.alimtalk.template;

import java.util.List;

public sealed interface GenerationResult {

    /** 오류 없이 생성됨. warnings는 경고 수준 항목. */
    record Success(TemplateDraft draft, List<String> warnings, int attempts) implements GenerationResult {}

    /** LLM이 생성을 거절함 (reason: AD_CONTENT | UNCLEAR). */
    record Rejected(String reason, String message) implements GenerationResult {}

    /** 재생성 후에도 오류 수준 위반이 남음. draft는 마지막 결과. */
    record RuleViolation(TemplateDraft draft, List<String> errors, List<String> warnings, int attempts)
            implements GenerationResult {}
}
