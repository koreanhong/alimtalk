package com.alimtalk.template;

import java.util.Map;

/** 템플릿 폼 값. 필드 이름은 화면(index.html)의 state 객체와 같다. */
public record TemplateDraft(
        String category,
        String name,
        String code,
        String msgType,
        String emph,
        String title,
        String sub,
        String content,
        String extra,
        boolean security,
        Map<String, String> samples) {}
