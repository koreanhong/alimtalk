package com.alimtalk.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alimtalk.llm.LlmClient;
import com.alimtalk.llm.LlmException;
import com.alimtalk.llm.LlmOutput;
import com.alimtalk.llm.LlmProperties;
import com.alimtalk.llm.LlmResponse;
import com.alimtalk.llm.LlmUsage;
import com.alimtalk.llm.PromptBuilder;
import com.alimtalk.rules.RuleLoader;
import com.alimtalk.rules.RuleValidator;
import com.alimtalk.usage.UsageLogProperties;
import com.alimtalk.usage.UsageRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemplateGenerationServiceTest {

    private static final LlmProperties PROPS = new LlmProperties("claude-opus-5-5", "medium",
            Duration.ofSeconds(60), Duration.ofSeconds(120), 2, 16000);

    private final RuleLoader loader = new RuleLoader();
    private final MutableClock clock = new MutableClock();
    private final ScriptedLlm llm = new ScriptedLlm(clock);
    private static final UsageLogProperties USAGE_PROPS = new UsageLogProperties("logs/llm-usage.csv",
            new UsageLogProperties.Price(4.0, 20.0, 0.2, 5.0));
    /** 가짜 LLM이 호출마다 돌려주는 토큰 수. */
    private static final LlmUsage PER_CALL = new LlmUsage(10, 100, 5000, 0);

    private final List<UsageRecord> recorded = new ArrayList<>();
    private final TemplateGenerationService service = new TemplateGenerationService(
            llm, new PromptBuilder(loader), new RuleValidator(loader.ruleSet()), PROPS, clock,
            recorded::add, USAGE_PROPS);

    private static LlmOutput ok(String content) {
        return new LlmOutput(LlmOutput.Status.OK, "", new LlmOutput.DraftOut("002001", "주문 완료 안내", "ORDER_COMPLETE_01",
                LlmOutput.MsgType.BA, LlmOutput.Emph.NONE, "", "", content, "", false, List.of(new LlmOutput.Sample("고객명", "홍길동"))));
    }

    private static LlmOutput valid() {
        return ok("#{고객명}님, 주문이 접수되었습니다.");
    }

    private static LlmOutput tooLong() {
        return ok("#{고객명}님, " + "가".repeat(1100));
    }

    @Test
    void firstAttemptSuccess() throws Exception {
        llm.then(valid());

        GenerationResult r = service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(r).isInstanceOf(GenerationResult.Success.class);
        assertThat(((GenerationResult.Success) r).attempts()).isEqualTo(1);
    }

    @Test
    void retriesThenSuccess() throws Exception {
        llm.then(tooLong()).then(valid());

        GenerationResult r = service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(r).isInstanceOf(GenerationResult.Success.class);
        assertThat(((GenerationResult.Success) r).attempts()).isEqualTo(2);
        assertThat(llm.userMessages.get(1)).contains("최대 1,000자");
    }

    @Test
    void ruleViolationAfterThreeAttempts() throws Exception {
        llm.then(tooLong()).then(tooLong()).then(ok("#{고객명}님, " + "나".repeat(1100)));

        GenerationResult r = service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(r).isInstanceOf(GenerationResult.RuleViolation.class);
        GenerationResult.RuleViolation v = (GenerationResult.RuleViolation) r;
        assertThat(v.attempts()).isEqualTo(3);
        assertThat(v.draft().content()).contains("나");
        assertThat(v.errors()).isNotEmpty();
        assertThat(llm.userMessages).hasSize(3);
    }

    @Test
    void stopsRetryingWhenBudgetLow() throws Exception {
        llm.after(Duration.ofSeconds(70)).then(tooLong()).then(valid());

        GenerationResult r = service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(r).isInstanceOf(GenerationResult.RuleViolation.class);
        assertThat(((GenerationResult.RuleViolation) r).attempts()).isEqualTo(1);
        assertThat(llm.userMessages).hasSize(1);
    }

    private static LlmOutput withMessage(LlmOutput out, String message) {
        return new LlmOutput(out.status(), message, out.draft());
    }

    @Test
    void assumptionMessageBecomesFirstWarning() throws Exception {
        llm.then(withMessage(ok("#{고객명}님, 주문이 접수되었습니다. 할인 금액: #{고객명}"), "주문 완료 안내로 가정했습니다."));

        GenerationResult r = service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(r).isInstanceOf(GenerationResult.Success.class);
        List<String> warnings = ((GenerationResult.Success) r).warnings();
        assertThat(warnings.get(0)).isEqualTo("주문 완료 안내로 가정했습니다.");
        assertThat(warnings).hasSize(2); // 가정 + 주의 단어(할인)
    }

    @Test
    void assumptionMessageKeptOnRuleViolation() throws Exception {
        llm.then(withMessage(tooLong(), "주문 완료 안내로 가정했습니다."))
                .then(withMessage(tooLong(), "주문 완료 안내로 가정했습니다."))
                .then(withMessage(tooLong(), "주문 완료 안내로 가정했습니다."));

        GenerationResult r = service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(r).isInstanceOf(GenerationResult.RuleViolation.class);
        assertThat(((GenerationResult.RuleViolation) r).warnings()).first().isEqualTo("주문 완료 안내로 가정했습니다.");
    }

    @Test
    void blankMessageAddsNoWarning() throws Exception {
        llm.then(withMessage(valid(), "  "));

        GenerationResult r = service.generate("주문 완료 안내 메시지", "마이스토어");

        assertThat(((GenerationResult.Success) r).warnings()).isEmpty();
    }

    // ---------- 사용량 기록 ----------

    @Test
    void recordsSuccessWithSummedUsageElapsedAndCost() throws Exception {
        llm.after(Duration.ofSeconds(30)).then(tooLong()).then(valid());

        service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(recorded).hasSize(1);
        UsageRecord r = recorded.get(0);
        assertThat(r.result()).isEqualTo("SUCCESS");
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.elapsedMs()).isEqualTo(60_000);
        assertThat(r.usage()).isEqualTo(new LlmUsage(20, 200, 10000, 0));
        assertThat(r.costUsd()).isCloseTo((20 * 4.0 + 200 * 20.0 + 10000 * 0.2) / 1_000_000, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(r.model()).isEqualTo("claude-opus-5-5");
        assertThat(r.effort()).isEqualTo("medium");
        assertThat(r.category()).isEqualTo("002001");
        assertThat(r.msgType()).isEqualTo("BA");
        assertThat(r.emph()).isEqualTo("NONE");
        assertThat(r.prompt()).isEqualTo("주문 관련 메시지 세팅해줘");
    }

    @Test
    void recordsRejectionWithoutDraftFields() throws Exception {
        llm.then(new LlmOutput(LlmOutput.Status.AD_CONTENT, "광고 불가", null));

        service.generate("이번 주말 할인 행사 홍보해줘", "마이스토어");

        UsageRecord r = recorded.get(0);
        assertThat(r.result()).isEqualTo("AD_CONTENT");
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(r.usage()).isEqualTo(PER_CALL);
        assertThat(r.category()).isNull();
    }

    @Test
    void recordsRuleViolation() throws Exception {
        llm.then(tooLong()).then(tooLong()).then(tooLong());

        service.generate("이용약관 전부 안내", "마이스토어");

        assertThat(recorded.get(0).result()).isEqualTo("RULE_VIOLATION");
        assertThat(recorded.get(0).attempts()).isEqualTo(3);
    }

    @Test
    void recordsErrorAndStillThrows() {
        llm.thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "timeout"));

        assertThatThrownBy(() -> service.generate("주문 관련 메시지 세팅해줘", "마이스토어"))
                .isInstanceOf(LlmException.class);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).result()).isEqualTo("ERROR_TIMEOUT");
        assertThat(recorded.get(0).usage()).isEqualTo(LlmUsage.ZERO);
    }

    @Test
    void recorderFailureDoesNotBreakGeneration() throws Exception {
        TemplateGenerationService failing = new TemplateGenerationService(
                llm, new PromptBuilder(loader), new RuleValidator(loader.ruleSet()), PROPS, clock,
                rec -> { throw new IllegalStateException("disk full"); }, USAGE_PROPS);
        llm.then(valid());

        assertThat(failing.generate("주문 완료 안내", "마이스토어")).isInstanceOf(GenerationResult.Success.class);
    }

    @Test
    void adContentIsRejectedWithoutValidation() throws Exception {
        llm.then(new LlmOutput(LlmOutput.Status.AD_CONTENT, "알림톡으로는 광고를 보낼 수 없습니다.", null));

        GenerationResult r = service.generate("할인 쿠폰 홍보 보내줘", "마이스토어");

        assertThat(r).isEqualTo(new GenerationResult.Rejected("AD_CONTENT", "알림톡으로는 광고를 보낼 수 없습니다."));
        assertThat(llm.userMessages).hasSize(1);
    }

    @Test
    void timeoutOnFirstCallPropagates() {
        llm.thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "timeout"));

        assertThatThrownBy(() -> service.generate("주문 관련 메시지 세팅해줘", "마이스토어"))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).kind())
                .isEqualTo(LlmException.Kind.TIMEOUT);
    }

    @Test
    void timeoutAfterAPreviousDraftReturnsViolation() throws Exception {
        llm.then(tooLong()).thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "timeout"));

        GenerationResult r = service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(r).isInstanceOf(GenerationResult.RuleViolation.class);
        assertThat(((GenerationResult.RuleViolation) r).attempts()).isEqualTo(1);
    }

    @Test
    void nullFieldsAreTreatedAsEmpty() throws Exception {
        llm.then(new LlmOutput(LlmOutput.Status.OK, null, new LlmOutput.DraftOut("002001", "주문 완료 안내", "ORDER_COMPLETE_01",
                LlmOutput.MsgType.BA, LlmOutput.Emph.NONE, null, null, "#{고객명}님, 주문이 접수되었습니다.", null, false,
                List.of(new LlmOutput.Sample("고객명", "홍길동")))));

        GenerationResult r = service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(r).isInstanceOf(GenerationResult.Success.class);
    }

    @Test
    void callTimeoutNeverExceedsRemainingBudget() throws Exception {
        llm.after(Duration.ofSeconds(50)).then(tooLong()).then(valid());

        service.generate("주문 관련 메시지 세팅해줘", "마이스토어");

        assertThat(llm.timeouts.get(0)).isEqualTo(Duration.ofSeconds(60));
        assertThat(llm.timeouts.get(1)).isEqualTo(Duration.ofSeconds(60));
    }

    /** 미리 정한 순서대로 응답하고, 호출마다 시계를 진행시키는 가짜 LLM. */
    static class ScriptedLlm implements LlmClient {
        private final MutableClock clock;
        private final Deque<Object> script = new ArrayDeque<>();
        private Duration delay = Duration.ZERO;
        final List<String> userMessages = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();

        ScriptedLlm(MutableClock clock) {
            this.clock = clock;
        }

        ScriptedLlm after(Duration d) {
            this.delay = d;
            return this;
        }

        ScriptedLlm then(LlmOutput out) {
            script.add(out);
            return this;
        }

        ScriptedLlm thenThrow(LlmException e) {
            script.add(e);
            return this;
        }

        @Override
        public LlmResponse generate(String system, String user, Duration timeout) throws LlmException {
            userMessages.add(user);
            timeouts.add(timeout);
            clock.advance(delay);
            Object next = script.poll();
            if (next instanceof LlmException e) {
                throw e;
            }
            return new LlmResponse((LlmOutput) next, PER_CALL);
        }
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-12T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
