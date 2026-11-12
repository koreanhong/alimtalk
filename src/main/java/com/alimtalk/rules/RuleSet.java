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
        int promptLengthTarget,
        Buttons buttons,
        ItemList itemList) {

    public record Meta(List<String> source, String checkedAt, String note) {}

    public record Limits(int content, int extra, int title, int sub, int name, int code, int vars) {}

    /** 버튼 제한 (수작업 전용, 자동 작성 대상 아님). */
    public record Buttons(int nameMax, int max) {}

    /** 아이템리스트형 강조 제한 (수작업 전용). hl* = 아이템 하이라이트, *Thumb = 썸네일이 있을 때. */
    public record ItemList(int header, int hlTitle, int hlTitleThumb, int hlDesc, int hlDescThumb, int min, int max) {}
}
