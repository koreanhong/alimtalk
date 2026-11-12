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
    void describesApiErrorWithTypeMessageAndRequestId() {
        com.anthropic.errors.BadRequestException e = com.anthropic.errors.BadRequestException.builder()
                .headers(com.anthropic.core.http.Headers.builder().put("request-id", "req_test123").build())
                .body(com.anthropic.core.JsonValue.from(Map.of(
                        "type", "error",
                        "error", Map.of("type", "invalid_request_error",
                                "message", "Your credit balance is too low to access the Anthropic API."))))
                .build();

        String d = ClaudeLlmClient.describe(e);

        assertThat(d).contains("status=400")
                .contains("invalid_request_error")
                .contains("credit balance is too low")
                .contains("req_test123");
    }

    @Test
    void describeTruncatesLongApiMessage() {
        com.anthropic.errors.BadRequestException e = com.anthropic.errors.BadRequestException.builder()
                .headers(com.anthropic.core.http.Headers.builder().build())
                .body(com.anthropic.core.JsonValue.from(Map.of(
                        "type", "error",
                        "error", Map.of("type", "invalid_request_error", "message", "x".repeat(1000)))))
                .build();

        assertThat(ClaudeLlmClient.describe(e).length()).isLessThan(600);
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
