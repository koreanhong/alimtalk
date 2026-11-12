package com.alimtalk.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 생성 요청별 사용량 기록 설정.
 * price는 100만 토큰당 USD 단가이며, 기록되는 비용은 이 단가로 계산한 추정치다(실제 청구액은 Claude Console에서 확인).
 */
@ConfigurationProperties("alimtalk.usage-log")
public record UsageLogProperties(String path, Price price) {

    public record Price(double inputPerMtok, double outputPerMtok, double cacheReadPerMtok, double cacheWritePerMtok) {}
}
