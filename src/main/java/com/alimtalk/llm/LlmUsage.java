package com.alimtalk.llm;

/** Claude 응답의 usage 토큰 수(입력, 출력, 캐시 읽기, 캐시 저장). */
public record LlmUsage(long input, long output, long cacheRead, long cacheWrite) {

    public static final LlmUsage ZERO = new LlmUsage(0, 0, 0, 0);

    public LlmUsage plus(LlmUsage other) {
        return new LlmUsage(input + other.input, output + other.output,
                cacheRead + other.cacheRead, cacheWrite + other.cacheWrite);
    }
}
