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
        assertThat(rules.msgTypes()).isEqualTo(List.of("BA", "EX", "AD", "MI"));
        assertThat(rules.emphTypes()).isEqualTo(List.of("NONE", "TEXT", "IMAGE", "ITEM_LIST"));
        assertThat(rules.itemList().sumDescPattern()).isNotBlank();
        assertThat(rules.categories()).hasSize(7);
    }

    @Test
    void loadsManualOnlyLimitsForButtonsAndItemList() {
        RuleSet rules = new RuleLoader().ruleSet();

        assertThat(rules.buttons().nameMax()).isEqualTo(14);
        assertThat(rules.buttons().max()).isEqualTo(5);
        RuleSet.ItemList item = rules.itemList();
        assertThat(item.header()).isEqualTo(16);
        assertThat(item.hlTitle()).isEqualTo(30);
        assertThat(item.hlTitleThumb()).isEqualTo(21);
        assertThat(item.hlDesc()).isEqualTo(19);
        assertThat(item.hlDescThumb()).isEqualTo(13);
        assertThat(item.min()).isEqualTo(2);
        assertThat(item.max()).isEqualTo(10);
    }
}
