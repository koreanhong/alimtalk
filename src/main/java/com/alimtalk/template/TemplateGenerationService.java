package com.alimtalk.template;

import com.alimtalk.llm.LlmClient;
import com.alimtalk.llm.LlmException;
import com.alimtalk.llm.LlmOutput;
import com.alimtalk.llm.LlmProperties;
import com.alimtalk.llm.PromptBuilder;
import com.alimtalk.rules.RuleValidator;
import com.alimtalk.rules.ValidationResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * LLM 생성 → 규칙 점검 → 위반 시 재생성.
 * 최대 1 + maxRetries회 호출하고, 전체 시간 예산 안에서만 재시도한다.
 */
@Service
public class TemplateGenerationService {

    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final RuleValidator validator;
    private final LlmProperties props;
    private final Clock clock;

    public TemplateGenerationService(LlmClient llm, PromptBuilder prompts, RuleValidator validator,
                                     LlmProperties props, Clock clock) {
        this.llm = llm;
        this.prompts = prompts;
        this.validator = validator;
        this.props = props;
        this.clock = clock;
    }

    public GenerationResult generate(String prompt, String channelName) throws LlmException {
        Instant deadline = clock.instant().plus(props.totalBudget());
        int maxAttempts = 1 + props.maxRetries();

        TemplateDraft lastDraft = null;
        ValidationResult lastResult = null;
        int attempts = 0;

        while (attempts < maxAttempts) {
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (attempts > 0 && remaining.compareTo(props.callTimeout()) < 0) {
                break;
            }
            Duration timeout = remaining.compareTo(props.callTimeout()) < 0 ? remaining : props.callTimeout();
            String user = lastDraft == null
                    ? prompts.user(prompt, channelName)
                    : prompts.retry(prompt, channelName, lastDraft, lastResult.errors());

            LlmOutput out;
            try {
                out = llm.generate(prompts.system(), user, timeout);
            } catch (LlmException e) {
                if (lastDraft == null) {
                    throw e;
                }
                break;
            }
            attempts++;

            if (out == null || out.status() == null) {
                throw new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답에 상태가 없습니다");
            }
            if (out.status() != LlmOutput.Status.OK) {
                return new GenerationResult.Rejected(out.status().name(), out.message() == null ? "" : out.message());
            }
            if (out.draft() == null) {
                throw new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답에 초안이 없습니다");
            }

            lastDraft = out.draft().toDraft();
            lastResult = validator.validate(lastDraft);
            if (!lastResult.hasErrors()) {
                return new GenerationResult.Success(lastDraft, lastResult.warnings(), attempts);
            }
        }
        return new GenerationResult.RuleViolation(lastDraft, lastResult.errors(), lastResult.warnings(), attempts);
    }
}
