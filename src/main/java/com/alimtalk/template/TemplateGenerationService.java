package com.alimtalk.template;

import com.alimtalk.llm.LlmClient;
import com.alimtalk.llm.LlmException;
import com.alimtalk.llm.LlmOutput;
import com.alimtalk.llm.LlmProperties;
import com.alimtalk.llm.LlmResponse;
import com.alimtalk.llm.LlmUsage;
import com.alimtalk.llm.PromptBuilder;
import com.alimtalk.rules.RuleValidator;
import com.alimtalk.rules.ValidationResult;
import com.alimtalk.usage.UsageCost;
import com.alimtalk.usage.UsageLogProperties;
import com.alimtalk.usage.UsageRecord;
import com.alimtalk.usage.UsageRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM 생성 → 규칙 점검 → 위반 시 재생성.
 * 최대 1 + maxRetries회 호출하고, 전체 시간 예산 안에서만 재시도한다.
 */
@Service
public class TemplateGenerationService {

    private static final Logger log = LoggerFactory.getLogger(TemplateGenerationService.class);

    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final RuleValidator validator;
    private final LlmProperties props;
    private final Clock clock;
    private final UsageRecorder usageRecorder;
    private final UsageLogProperties usageProps;

    public TemplateGenerationService(LlmClient llm, PromptBuilder prompts, RuleValidator validator,
                                     LlmProperties props, Clock clock,
                                     UsageRecorder usageRecorder, UsageLogProperties usageProps) {
        this.llm = llm;
        this.prompts = prompts;
        this.validator = validator;
        this.props = props;
        this.clock = clock;
        this.usageRecorder = usageRecorder;
        this.usageProps = usageProps;
    }

    /** 생성 1건을 처리하고, 결과와 관계없이(오류 포함) 사용량을 1줄 기록한다. */
    public GenerationResult generate(String prompt, String channelName) throws LlmException {
        Instant started = clock.instant();
        Tally tally = new Tally();
        try {
            GenerationResult result = run(prompt, channelName, started, tally);
            record(started, prompt, tally, resultName(result), draftOf(result));
            return result;
        } catch (LlmException e) {
            record(started, prompt, tally, "ERROR_" + e.kind().name(), null);
            throw e;
        }
    }

    private GenerationResult run(String prompt, String channelName, Instant started, Tally tally) throws LlmException {
        Instant deadline = started.plus(props.totalBudget());
        int maxAttempts = 1 + props.maxRetries();

        TemplateDraft lastDraft = null;
        ValidationResult lastResult = null;
        String lastAssumption = "";
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
                LlmResponse response = llm.generate(prompts.system(), user, timeout);
                out = response.output();
                tally.usage = tally.usage.plus(response.usage() == null ? LlmUsage.ZERO : response.usage());
            } catch (LlmException e) {
                if (lastDraft == null) {
                    throw e;
                }
                break;
            }
            attempts++;
            tally.attempts = attempts;

            if (out == null || out.status() == null) {
                throw new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답에 상태가 없습니다");
            }
            if (out.status() != LlmOutput.Status.OK) {
                String reason = out.status() == LlmOutput.Status.ASK ? "UNCLEAR" : out.status().name();
                return new GenerationResult.Rejected(reason, out.message() == null ? "" : out.message());
            }
            if (out.draft() == null) {
                throw new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답에 초안이 없습니다");
            }

            lastDraft = out.draft().toDraft();
            lastResult = validator.validate(lastDraft);
            lastAssumption = out.message() == null ? "" : out.message().strip();
            if (!lastResult.hasErrors()) {
                return new GenerationResult.Success(lastDraft, withAssumption(lastAssumption, lastResult.warnings()), attempts);
            }
        }
        return new GenerationResult.RuleViolation(lastDraft, lastResult.errors(),
                withAssumption(lastAssumption, lastResult.warnings()), attempts);
    }

    /** 요청 1건 동안 누적되는 토큰과 시도 횟수. */
    private static final class Tally {
        LlmUsage usage = LlmUsage.ZERO;
        int attempts;
    }

    private void record(Instant started, String prompt, Tally tally, String result, TemplateDraft draft) {
        try {
            long elapsedMs = Duration.between(started, clock.instant()).toMillis();
            usageRecorder.record(new UsageRecord(started, result, tally.attempts, elapsedMs, tally.usage,
                    UsageCost.estimateUsd(tally.usage, usageProps.price()), props.model(), props.effort(),
                    draft == null ? null : draft.category(),
                    draft == null ? null : draft.msgType(),
                    draft == null ? null : draft.emph(),
                    prompt));
        } catch (RuntimeException e) {
            // 기록 실패가 생성 응답을 막지 않도록 한다
            log.warn("사용량 기록 실패: {}", e.getClass().getSimpleName());
        }
    }

    private static String resultName(GenerationResult r) {
        return switch (r) {
            case GenerationResult.Success s -> "SUCCESS";
            case GenerationResult.RuleViolation v -> "RULE_VIOLATION";
            case GenerationResult.Rejected j -> j.reason();
        };
    }

    private static TemplateDraft draftOf(GenerationResult r) {
        return switch (r) {
            case GenerationResult.Success s -> s.draft();
            case GenerationResult.RuleViolation v -> v.draft();
            case GenerationResult.Rejected j -> null;
        };
    }

    /** 애매한 요청을 Claude가 어떤 경우로 가정해 작성했는지(예: "주문 완료 안내로 가정했습니다.")를 경고 맨 앞에 둔다. */
    private static List<String> withAssumption(String assumption, List<String> warnings) {
        if (assumption.isBlank()) {
            return warnings;
        }
        List<String> merged = new ArrayList<>(warnings.size() + 1);
        merged.add(assumption);
        merged.addAll(warnings);
        return List.copyOf(merged);
    }
}
