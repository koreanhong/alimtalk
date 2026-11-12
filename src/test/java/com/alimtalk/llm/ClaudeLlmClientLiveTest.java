package com.alimtalk.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.alimtalk.rules.RuleLoader;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 실제 Claude API를 호출한다(비용 발생). LIVE_LLM_TEST=true와 ANTHROPIC_API_KEY가 있을 때만 실행. */
@EnabledIfEnvironmentVariable(named = "LIVE_LLM_TEST", matches = "true")
class ClaudeLlmClientLiveTest {

    @Test
    void generatesOrderTemplate() throws Exception {
        LlmProperties props = new LlmProperties("claude-opus-5-5", "medium",
                Duration.ofSeconds(60), Duration.ofSeconds(120), 2, 16000);
        PromptBuilder prompts = new PromptBuilder(new RuleLoader());
        ClaudeLlmClient client = new ClaudeLlmClient(props);

        LlmOutput out = client.generate(prompts.system(), prompts.user("주문 관련 메시지 세팅해줘", "마이스토어"),
                Duration.ofSeconds(60));

        assertThat(out.status()).isEqualTo(LlmOutput.Status.OK);
        assertThat(out.draft().msgType()).isNotNull();
    }
}
