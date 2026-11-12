package com.alimtalk.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Claude Messages API 구현. 키는 환경변수 ANTHROPIC_API_KEY에서 SDK가 읽는다.
 * SDK 자체 재시도(429/5xx)는 그대로 두고, 전체 대기 시간은 timeout으로 끊는다.
 */
@Component
@Profile("!fake-llm")
public class ClaudeLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLlmClient.class);

    private final LlmProperties props;
    private volatile AnthropicClient client;

    public ClaudeLlmClient(LlmProperties props) {
        this.props = props;
    }

    @Override
    public LlmOutput generate(String system, String user, Duration timeout) throws LlmException {
        StructuredMessageCreateParams<LlmOutput> params = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(props.maxTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(user)
                .outputConfig(StructuredOutputConfig.<LlmOutput>builder()
                        .format(LlmOutput.class)
                        .effort(OutputConfig.Effort.of(props.effort()))
                        .build())
                .build();

        CompletableFuture<StructuredMessage<LlmOutput>> future = client().async().messages()
                .create(params, RequestOptions.builder().timeout(timeout).build());
        StructuredMessage<LlmOutput> response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmException(LlmException.Kind.TIMEOUT, "LLM 응답 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new LlmException(LlmException.Kind.API_ERROR, "LLM 호출이 중단되었습니다");
        } catch (ExecutionException e) {
            throw classify(e.getCause());
        }

        log.info("LLM usage: input={}, output={}, cacheRead={}, cacheWrite={}",
                response.usage().inputTokens(), response.usage().outputTokens(),
                response.usage().cacheReadInputTokens().orElse(0L),
                response.usage().cacheCreationInputTokens().orElse(0L));

        Optional<StopReason> stop = response.stopReason();
        if (stop.isPresent() && stop.get().equals(StopReason.REFUSAL)) {
            throw new LlmException(LlmException.Kind.REFUSAL, "LLM이 요청을 거부했습니다");
        }
        if (stop.isPresent() && stop.get().equals(StopReason.MAX_TOKENS)) {
            throw new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답이 잘렸습니다");
        }
        try {
            return response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .orElseThrow(() -> new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답에 결과가 없습니다"));
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답 형식이 올바르지 않습니다");
        }
    }

    private static LlmException classify(Throwable cause) {
        if (cause instanceof AnthropicIoException && hasTimeoutCause(cause)) {
            return new LlmException(LlmException.Kind.TIMEOUT, "LLM 응답 시간 초과");
        }
        if (cause instanceof AnthropicInvalidDataException) {
            return new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답 형식이 올바르지 않습니다");
        }
        if (cause instanceof AnthropicServiceException service) {
            log.warn("LLM API 오류: status={}", service.statusCode());
            return new LlmException(LlmException.Kind.API_ERROR, "LLM API 오류");
        }
        log.warn("LLM 호출 실패: {}", cause == null ? "unknown" : cause.getClass().getName());
        return new LlmException(LlmException.Kind.API_ERROR, "LLM 호출 실패");
    }

    private static boolean hasTimeoutCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    /** 키가 없어도 앱이 뜨도록 첫 호출 때 만든다. */
    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.fromEnv();
                }
                c = client;
            }
        }
        return c;
    }
}
