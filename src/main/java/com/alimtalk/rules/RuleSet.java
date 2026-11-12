package com.alimtalk.rules;

import java.util.List;

public record RuleSet(
        Meta meta,
        Limits limits,
        List<String> categories,
        List<String> msgTypes,
        List<String> emphTypes,
        List<String> bannedPhrases,
        List<String> cautionWords,
        String codePattern,
        int promptLengthTarget) {

    public record Meta(List<String> source, String checkedAt, String note) {}

    public record Limits(int content, int extra, int title, int sub, int name, int code, int vars) {}
}
