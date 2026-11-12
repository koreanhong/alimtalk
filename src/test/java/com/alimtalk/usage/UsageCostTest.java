package com.alimtalk.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.alimtalk.llm.LlmUsage;
import org.junit.jupiter.api.Test;

class UsageCostTest {

    /** Claude Opus 5.5 단가(100만 토큰당 USD): 입력 4, 출력 20, 캐시 읽기 0.2, 캐시 저장(5분) 5. */
    private static final UsageLogProperties.Price OPUS_5_5 = new UsageLogProperties.Price(4.0, 20.0, 0.2, 5.0);

    @Test
    void estimatesUsdFromTokensAndUnitPrices() {
        LlmUsage usage = new LlmUsage(41, 343, 5472, 0);

        // (41*4 + 343*20 + 5472*0.2 + 0*5) / 1,000,000
        assertThat(UsageCost.estimateUsd(usage, OPUS_5_5)).isCloseTo(0.0081184, within(1e-9));
    }

    @Test
    void cacheWriteUsesItsOwnPrice() {
        assertThat(UsageCost.estimateUsd(new LlmUsage(0, 0, 0, 1_000_000), OPUS_5_5)).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void zeroUsageCostsNothing() {
        assertThat(UsageCost.estimateUsd(LlmUsage.ZERO, OPUS_5_5)).isZero();
    }
}
