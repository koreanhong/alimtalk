package com.alimtalk.template;

import com.alimtalk.llm.LlmClient;
import com.alimtalk.llm.LlmException;
import com.alimtalk.llm.LlmOutput;
import com.alimtalk.llm.LlmProperties;
import com.alimtalk.llm.LlmResponse;
import com.alimtalk.llm.LlmTurn;
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
import java.util.Map;
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

    private static final int FORCE_DRAFT_AFTER_QUESTIONS = 3;
    private static final int MAX_CHOICE_LENGTH = 20;

    /** 한 번에 채우기(기존 API). 질문(ASK)은 UNCLEAR 거절로 바꿔 기존 응답 계약을 유지한다. */
    public GenerationResult generate(String prompt, String channelName) throws LlmException {
        List<LlmTurn> turns = List.of(LlmTurn.user(prompts.user(prompt, channelName)));
        GenerationResult result = execute(turns, null, prompt, 1);
        if (result instanceof GenerationResult.Asked asked) {
            return new GenerationResult.Rejected("UNCLEAR", asked.question());
        }
        return result;
    }

    /**
     * 대화형 세팅 1턴. messages의 마지막은 user 발화여야 한다.
     * 현재 폼은 첫 user 턴 앞에 붙이고, 질문만 연속 3번이면 끝에 강제 작성 지시를 붙인다.
     */
    public GenerationResult chat(List<ChatTurnInput> messages, TemplateDraft form, String channelName) throws LlmException {
        List<LlmTurn> turns = new ArrayList<>();
        String context = prompts.channelLine(channelName) + prompts.formContext(form);
        boolean contextAttached = false;
        for (ChatTurnInput m : messages) {
            if (m.isUser()) {
                String text = contextAttached ? m.text() : context + "\n\n요청: " + m.text();
                contextAttached = true;
                turns.add(LlmTurn.user(text));
            } else {
                if (!contextAttached) {
                    turns.add(LlmTurn.user(context));
                    contextAttached = true;
                }
                turns.add(LlmTurn.assistant(m.text()));
            }
        }
        if (consecutiveQuestions(messages) >= FORCE_DRAFT_AFTER_QUESTIONS) {
            turns.add(LlmTurn.system(PromptBuilder.FORCE_DRAFT));
        }
        String lastUser = messages.isEmpty() ? "" : messages.get(messages.size() - 1).text();
        return execute(turns, form, lastUser, messages.size());
    }

    /** 마지막 질문 아닌 응답(DRAFT·REJECTED) 이후 연속된 질문(ASK) 응답 수. */
    private static int consecutiveQuestions(List<ChatTurnInput> messages) {
        int count = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatTurnInput m = messages.get(i);
            if (m.isUser()) {
                continue;
            }
            if (!m.isAsk()) {
                break;
            }
            count++;
        }
        return count;
    }

    /** 생성 1건을 처리하고, 결과와 관계없이(오류 포함) 사용량을 1줄 기록한다. */
    private GenerationResult execute(List<LlmTurn> turns, TemplateDraft form, String promptForLog, int turnCount)
            throws LlmException {
        Instant started = clock.instant();
        Tally tally = new Tally();
        try {
            GenerationResult result = run(turns, form, started, tally);
            record(started, promptForLog, tally, resultName(result), draftOf(result), modeOf(result), turnCount);
            return result;
        } catch (LlmException e) {
            record(started, promptForLog, tally, "ERROR_" + e.kind().name(), null, null, turnCount);
            throw e;
        } catch (RuntimeException e) {
            record(started, promptForLog, tally, "ERROR_UNEXPECTED", null, null, turnCount);
            throw e;
        }
    }

    private GenerationResult run(List<LlmTurn> baseTurns, TemplateDraft form, Instant started, Tally tally)
            throws LlmException {
        Instant deadline = started.plus(props.totalBudget());
        int maxAttempts = 1 + props.maxRetries();

        TemplateDraft lastDraft = null;
        ValidationResult lastResult = null;
        LlmOutput lastOut = null;
        int attempts = 0;

        while (attempts < maxAttempts) {
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (attempts > 0 && remaining.compareTo(props.callTimeout()) < 0) {
                break;
            }
            Duration timeout = remaining.compareTo(props.callTimeout()) < 0 ? remaining : props.callTimeout();
            List<LlmTurn> turns = lastDraft == null
                    ? baseTurns
                    : withRetryNote(baseTurns, prompts.retryNote(lastDraft, lastResult.errors()));

            LlmOutput out;
            try {
                LlmResponse response = llm.generate(prompts.system(), turns, timeout);
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
            if (out.status() == LlmOutput.Status.ASK) {
                return new GenerationResult.Asked(nz(out.message()), choicesOf(out));
            }
            if (out.status() != LlmOutput.Status.OK) {
                return new GenerationResult.Rejected(out.status().name(), nz(out.message()));
            }
            if (out.draft() == null) {
                throw new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답에 초안이 없습니다");
            }

            lastOut = out;
            lastDraft = out.draft().toDraft();
            lastResult = validator.validate(lastDraft);
            if (!lastResult.hasErrors()) {
                return new GenerationResult.Success(lastDraft, withTypeChange(lastResult.warnings(), form, out, lastDraft), attempts,
                        modeName(out), nz(out.message()).strip(), choicesOf(out));
            }
        }
        return new GenerationResult.RuleViolation(lastDraft, lastResult.errors(),
                withTypeChange(lastResult.warnings(), form, lastOut, lastDraft), attempts, modeName(lastOut), nz(lastOut.message()).strip(), choicesOf(lastOut));
    }

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "BA", "기본형", "EX", "부가정보형", "AD", "채널추가형", "MI", "복합형",
            "NONE", "선택 안 함", "TEXT", "강조 표기형", "IMAGE", "이미지형", "ITEM_LIST", "아이템리스트형");

    /** 다듬기 결과의 유형이 현재 폼과 다르면 경고를 붙인다(막지는 않는다, 설계 12.2). */
    private static List<String> withTypeChange(List<String> warnings, TemplateDraft form, LlmOutput out, TemplateDraft draft) {
        if (form == null || out == null || out.mode() != LlmOutput.Mode.POLISH || draft == null) {
            return warnings;
        }
        List<String> all = new ArrayList<>(warnings);
        typeChange("메시지 유형", form.msgType(), draft.msgType(), all);
        typeChange("강조 유형", form.emph(), draft.emph(), all);
        return List.copyOf(all);
    }

    private static void typeChange(String label, String before, String after, List<String> out) {
        if (before == null || before.isBlank() || after == null || before.equals(after)) {
            return;
        }
        out.add("%s이 %s에서 %s(으)로 바뀌었습니다. 의도한 변경인지 확인해 주세요."
                .formatted(label, TYPE_NAMES.getOrDefault(before, before), TYPE_NAMES.getOrDefault(after, after)));
    }

    /** 재작성 요청은 마지막 user 턴에 덧붙인다(user 턴이 연달아 오지 않게, 강제 작성 지시는 맨 끝 유지). */
    private static List<LlmTurn> withRetryNote(List<LlmTurn> base, String note) {
        List<LlmTurn> turns = new ArrayList<>(base);
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role() == LlmTurn.Role.USER) {
                turns.set(i, LlmTurn.user(turns.get(i).text() + "\n\n" + note));
                break;
            }
        }
        return turns;
    }

    private static String modeName(LlmOutput out) {
        return out == null || out.mode() == null ? "NEW" : out.mode().name();
    }

    private static List<String> choicesOf(LlmOutput out) {
        if (out == null || out.choices() == null) {
            return List.of();
        }
        return out.choices().stream()
                .filter(c -> c != null && !c.isBlank() && c.codePointCount(0, c.length()) <= MAX_CHOICE_LENGTH)
                .limit(6).toList();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 요청 1건 동안 누적되는 토큰과 시도 횟수. */
    private static final class Tally {
        LlmUsage usage = LlmUsage.ZERO;
        int attempts;
    }

    private void record(Instant started, String prompt, Tally tally, String result, TemplateDraft draft,
                        String mode, int turns) {
        try {
            long elapsedMs = Duration.between(started, clock.instant()).toMillis();
            usageRecorder.record(new UsageRecord(started, result, tally.attempts, elapsedMs, tally.usage,
                    UsageCost.estimateUsd(tally.usage, usageProps.price()), props.model(), props.effort(),
                    draft == null ? null : draft.category(),
                    draft == null ? null : draft.msgType(),
                    draft == null ? null : draft.emph(),
                    prompt, mode, turns));
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
            case GenerationResult.Asked a -> "ASK";
        };
    }

    private static TemplateDraft draftOf(GenerationResult r) {
        return switch (r) {
            case GenerationResult.Success s -> s.draft();
            case GenerationResult.RuleViolation v -> v.draft();
            case GenerationResult.Rejected j -> null;
            case GenerationResult.Asked a -> null;
        };
    }

    private static String modeOf(GenerationResult r) {
        return switch (r) {
            case GenerationResult.Success s -> s.mode();
            case GenerationResult.RuleViolation v -> v.mode();
            case GenerationResult.Rejected j -> null;
            case GenerationResult.Asked a -> null;
        };
    }
}
