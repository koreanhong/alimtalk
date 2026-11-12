package com.alimtalk.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaudeLlmClientTest {

    private static final LlmProperties PROPS = new LlmProperties("claude-opus-5-5", "medium",
            Duration.ofSeconds(60), Duration.ofSeconds(120), 2, 16000);

    @Test
    void credentialsMissingWhenNoKeyOrToken() {
        assertThat(ClaudeLlmClient.credentialsMissing(Map.of())).isTrue();
        assertThat(ClaudeLlmClient.credentialsMissing(Map.of("ANTHROPIC_API_KEY", "  "))).isTrue();
        assertThat(ClaudeLlmClient.credentialsMissing(Map.of("ANTHROPIC_API_KEY", "x"))).isFalse();
        assertThat(ClaudeLlmClient.credentialsMissing(Map.of("ANTHROPIC_AUTH_TOKEN", "x"))).isFalse();
    }

    @Test
    void clientCreationFailureBecomesApiErrorNot500() {
        ClaudeLlmClient client = new ClaudeLlmClient(PROPS, () -> {
            throw new IllegalStateException("INTERNAL-xyz");
        });

        assertThatThrownBy(() -> client.generate("system", "user", Duration.ofSeconds(1)))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    assertThat(((LlmException) e).kind()).isEqualTo(LlmException.Kind.API_ERROR);
                    assertThat(e.getMessage()).doesNotContain("INTERNAL-xyz");
                });
    }
}
