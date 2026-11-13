package com.alimtalk.template;

import java.util.Map;

/** 화면의 발신 채널 선택지와 같다. 채널은 사용자가 고르며 LLM에는 표시명만 전달한다. */
public final class Channels {

    private static final Map<String, String> NAMES = Map.of(
            "@mystore", "아디다스",
            "@hotvpn", "PMK",
            "@testchannel", "테스트채널");

    private Channels() {}

    /** 등록된 채널의 표시명. 임의 문자열이 프롬프트에 들어가지 않도록 미등록·null은 빈 문자열. */
    public static String displayName(String code) {
        return code == null ? "" : NAMES.getOrDefault(code, "");
    }
}
