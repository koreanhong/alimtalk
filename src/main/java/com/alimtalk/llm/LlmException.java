package com.alimtalk.llm;

/** LLM 호출 실패. 메시지에는 API 키나 응답 헤더를 담지 않는다. */
public class LlmException extends Exception {

    public enum Kind { TIMEOUT, API_ERROR, REFUSAL, INVALID_OUTPUT }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
