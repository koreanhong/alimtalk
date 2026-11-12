package com.alimtalk.rules;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RulesConfig {

    @Bean
    public RuleSet ruleSet(RuleLoader loader) {
        return loader.ruleSet();
    }
}
