package com.alimtalk.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuleLoaderTest {

    @Test
    void loadsLimitsFromClasspath() {
        RuleLoader loader = new RuleLoader();

        RuleSet rules = loader.ruleSet();

        assertThat(rules.limits().content()).isEqualTo(1000);
        assertThat(rules.limits().vars()).isEqualTo(30);
        assertThat(rules.msgTypes()).isEqualTo(List.of("BA", "EX"));
        assertThat(rules.categories()).hasSize(7);
    }
}
