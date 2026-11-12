package com.alimtalk.llm;

/** Claude 호출 1회의 결과와 사용 토큰. */
public record LlmResponse(LlmOutput output, LlmUsage usage) {}
