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
import java.util.Set;
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

    private static final Set<String> OUT_OF_SCOPE_MSG_TYPES = Set.of("AD", "MI");
    private static final Set<String> OUT_OF_SCOPE_EMPH = Set.of("IMAGE", "ITEM_LIST");
    private static final Set<String> EXTRA_MSG_TYPES = Set.of("EX", "MI");
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
            lastDraft = keepFormTypes(out.draft().toDraft(), form, out);
            lastResult = validator.validate(checkedAsInScope(lastDraft));
            if (!lastResult.hasErrors()) {
                return new GenerationResult.Success(lastDraft, lastResult.warnings(), attempts,
                        modeName(out), nz(out.message()).strip(), choicesOf(out));
            }
        }
        return new GenerationResult.RuleViolation(lastDraft, lastResult.errors(),
                lastResult.warnings(), attempts, modeName(lastOut), nz(lastOut.message()).strip(), choicesOf(lastOut));
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

    /**
     * 다듬기에서 폼의 유형이 자동 세팅 범위 밖(채널추가형·복합형, 이미지형·아이템리스트형)이면 텍스트만 다듬은 것으로 보고
     * 폼의 유형을 그대로 두고, 화면에 보이지 않는 칸·Claude가 비운 칸은 폼 값을 유지한 초안을 만든다(설계 5.3).
     * 규칙 점검·재생성·반환 모두 이 초안을 쓴다. 범위 안 유형이면 Claude 초안 그대로.
     */
    private static TemplateDraft keepFormTypes(TemplateDraft draft, TemplateDraft form, LlmOutput out) {
        if (form == null || out == null || out.mode() != LlmOutput.Mode.POLISH
                || !(OUT_OF_SCOPE_MSG_TYPES.contains(nz(form.msgType())) || OUT_OF_SCOPE_EMPH.contains(nz(form.emph())))) {
            return draft;
        }
        String msgType = blankOr(form.msgType(), draft.msgType());
        String emph = blankOr(form.emph(), draft.emph());
        // 부가정보 칸이 있는 유형은 Claude가 비웠을 때만 폼 값, 칸이 없는 유형은 항상 폼 값(보이지 않는 칸에 넣지 않는다)
        String extra = EXTRA_MSG_TYPES.contains(msgType) ? blankOr(draft.extra(), form.extra()) : form.extra();
        boolean textEmph = "TEXT".equals(emph);
        String title = textEmph ? blankOr(draft.title(), form.title()) : form.title();
        String sub = textEmph ? blankOr(draft.sub(), form.sub()) : form.sub();
        return new TemplateDraft(draft.category(), draft.name(), draft.code(), msgType, emph, title, sub,
                draft.content(), extra, draft.security(), draft.samples());
    }

    /** 점검기는 범위 안 유형만 알므로, 범위 밖 유형은 같은 칸 구성의 범위 안 유형으로 바꿔 점검한다(MI→EX, AD→BA, IMAGE·ITEM_LIST→NONE). */
    private static TemplateDraft checkedAsInScope(TemplateDraft d) {
        String msgType = switch (nz(d.msgType())) {
            case "MI" -> "EX";
            case "AD" -> "BA";
            default -> d.msgType();
        };
        String emph = OUT_OF_SCOPE_EMPH.contains(nz(d.emph())) ? "NONE" : d.emph();
        return new TemplateDraft(d.category(), d.name(), d.code(), msgType, emph, d.title(), d.sub(),
                d.content(), d.extra(), d.security(), d.samples());
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
