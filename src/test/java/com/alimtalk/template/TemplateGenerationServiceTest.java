package com.alimtalk.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alimtalk.llm.LlmClient;
import com.alimtalk.llm.LlmException;
import com.alimtalk.llm.LlmOutput;
import com.alimtalk.llm.LlmProperties;
import com.alimtalk.llm.PromptBuilder;
import com.alimtalk.rules.RuleLoader;
import com.alimtalk.rules.RuleValidator;
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
    private final TemplateGenerationService service = new TemplateGenerationService(
            llm, new PromptBuilder(loader), new RuleValidator(loader.ruleSet()), PROPS, clock);

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
        public LlmOutput generate(String system, String user, Duration timeout) throws LlmException {
            userMessages.add(user);
            timeouts.add(timeout);
            clock.advance(delay);
            Object next = script.poll();
            if (next instanceof LlmException e) {
                throw e;
            }
            return (LlmOutput) next;
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
