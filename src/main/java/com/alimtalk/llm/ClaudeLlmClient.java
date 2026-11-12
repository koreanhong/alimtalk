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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Supplier<AnthropicClient> clientFactory;
    private volatile AnthropicClient client;

    @Autowired
    public ClaudeLlmClient(LlmProperties props) {
        this(props, AnthropicOkHttpClient::fromEnv);
    }

    /** 테스트에서 클라이언트 생성 실패를 흉내 내기 위한 생성자. */
    ClaudeLlmClient(LlmProperties props, Supplier<AnthropicClient> clientFactory) {
        this.props = props;
        this.clientFactory = clientFactory;
    }

    /** 키가 없어도 서버는 뜨지만, 첫 생성 요청에서 원인 모를 오류가 나지 않도록 시작 시 알린다(키 값은 출력하지 않음). */
    @PostConstruct
    void warnIfCredentialsMissing() {
        if (credentialsMissing(System.getenv())) {
            log.warn("ANTHROPIC_API_KEY(또는 ANTHROPIC_AUTH_TOKEN) 환경변수가 없습니다. 자연어 생성 요청은 오류로 응답합니다. "
                    + "키 없이 화면만 확인하려면 SPRING_PROFILES_ACTIVE=fake-llm 으로 실행하세요.");
        }
    }

    static boolean credentialsMissing(Map<String, String> env) {
        return isBlank(env.get("ANTHROPIC_API_KEY")) && isBlank(env.get("ANTHROPIC_AUTH_TOKEN"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public LlmResponse generate(String system, String user, Duration timeout) throws LlmException {
        try {
            return call(system, user, timeout);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            // 클라이언트 생성·요청 구성 실패도 502(LLM_ERROR)로 응답되도록 변환한다. 내부 메시지는 담지 않는다.
            log.warn("LLM 호출 준비 실패: {}", e.getClass().getName());
            throw new LlmException(LlmException.Kind.API_ERROR, "LLM 호출 실패");
        }
    }

    private LlmResponse call(String system, String user, Duration timeout) throws LlmException {
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

        LlmUsage usage = new LlmUsage(response.usage().inputTokens(), response.usage().outputTokens(),
                response.usage().cacheReadInputTokens().orElse(0L),
                response.usage().cacheCreationInputTokens().orElse(0L));
        log.info("LLM usage: input={}, output={}, cacheRead={}, cacheWrite={}",
                usage.input(), usage.output(), usage.cacheRead(), usage.cacheWrite());

        Optional<StopReason> stop = response.stopReason();
        if (stop.isPresent() && stop.get().equals(StopReason.REFUSAL)) {
            throw new LlmException(LlmException.Kind.REFUSAL, "LLM이 요청을 거부했습니다");
        }
        if (stop.isPresent() && stop.get().equals(StopReason.MAX_TOKENS)) {
            throw new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답이 잘렸습니다");
        }
        try {
            LlmOutput output = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .orElseThrow(() -> new LlmException(LlmException.Kind.INVALID_OUTPUT, "LLM 응답에 결과가 없습니다"));
            return new LlmResponse(output, usage);
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
            log.warn("LLM API 오류: {}", describe(service));
            return new LlmException(LlmException.Kind.API_ERROR, "LLM API 오류");
        }
        log.warn("LLM 호출 실패: {}", cause == null ? "unknown" : cause.getClass().getName());
        return new LlmException(LlmException.Kind.API_ERROR, "LLM 호출 실패");
    }

    /**
     * Anthropic이 돌려준 오류를 원인 파악용으로 요약한다(상태 코드, 오류 종류, 오류 설명, 요청 ID).
     * 요청 본문·헤더·키는 담지 않는다. 오류 설명은 길면 자른다. 이 문자열은 로그에만 쓰고 화면 응답에는 쓰지 않는다.
     */
    static String describe(AnthropicServiceException e) {
        String type = e.errorType().map(Object::toString).orElse("unknown");
        String message = e.apiMessage().orElse("");
        if (message.length() > 300) {
            message = message.substring(0, 300) + "…";
        }
        String requestId = e.requestId().orElse("-");
        return "status=%d, type=%s, message=%s, requestId=%s".formatted(e.statusCode(), type, message, requestId);
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
                    client = clientFactory.get();
                }
                c = client;
            }
        }
        return c;
    }
}
