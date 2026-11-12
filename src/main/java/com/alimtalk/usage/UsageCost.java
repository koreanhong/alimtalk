package com.alimtalk.usage;

import com.alimtalk.llm.LlmUsage;

/** 토큰 수 × 단가로 비용을 추정한다. Claude API 응답은 토큰 수만 주고 금액은 주지 않는다. */
public final class UsageCost {

    private UsageCost() {}

    public static double estimateUsd(LlmUsage u, UsageLogProperties.Price p) {
        return (u.input() * p.inputPerMtok()
                + u.output() * p.outputPerMtok()
                + u.cacheRead() * p.cacheReadPerMtok()
                + u.cacheWrite() * p.cacheWritePerMtok()) / 1_000_000;
    }
}
