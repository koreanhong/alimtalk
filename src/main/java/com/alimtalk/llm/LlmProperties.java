package com.alimtalk.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("alimtalk.llm")
public record LlmProperties(
        String model,
        String effort,
        Duration callTimeout,
        Duration totalBudget,
        int maxRetries,
        long maxTokens) {}
