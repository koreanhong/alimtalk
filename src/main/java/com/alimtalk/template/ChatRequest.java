package com.alimtalk.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 대화형 세팅 요청. 대화 기록은 화면이 보관해 매 턴 함께 보낸다. form은 현재 폼 텍스트 항목(없으면 빈 폼). */
public record ChatRequest(
        @NotNull(message = "대화 내용이 없습니다.")
        @Size(min = 1, max = 20, message = "대화는 1~20개 발화까지 보낼 수 있습니다.")
        List<@Valid Msg> messages,
        @Size(max = 50, message = "발신 채널 값이 너무 깁니다.")
        String channel,
        TemplateDraft form) {

    public record Msg(
            @NotNull @Pattern(regexp = "user|assistant", message = "발화 역할이 올바르지 않습니다.") String role,
            @NotBlank(message = "빈 발화는 보낼 수 없습니다.") @Size(max = 500, message = "발화는 500자 이내로 입력해 주세요.") String text,
            String kind) {}
}
