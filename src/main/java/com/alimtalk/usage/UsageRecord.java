package com.alimtalk.usage;

import com.alimtalk.llm.LlmUsage;
import java.time.Instant;

/**
 * 자연어 생성 요청 1건의 사용량 기록.
 * result: SUCCESS, RULE_VIOLATION, AD_CONTENT, UNCLEAR, ERROR_TIMEOUT, ERROR_API_ERROR 등.
 * usage는 재생성까지 합친 값, elapsedMs는 요청 전체 소요 시간. 초안이 없으면 category·msgType·emph는 null.
 */
public record UsageRecord(
        Instant at,
        String result,
        int attempts,
        long elapsedMs,
        LlmUsage usage,
        double costUsd,
        String model,
        String effort,
        String category,
        String msgType,
        String emph,
        String prompt) {}
