package com.alimtalk.template;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenerateRequest(
        @NotBlank(message = "요청 내용을 입력해 주세요.")
        @Size(max = 500, message = "요청은 500자 이내로 입력해 주세요.")
        String prompt,
        String channel) {}
