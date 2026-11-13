package com.alimtalk.llm;

/**
 * Claude에 보내는 대화 한 턴.
 * SYSTEM은 대화 중간의 운영 지시(예: "이번에는 반드시 작성")로, user 턴 뒤 마지막에만 둔다.
 */
public record LlmTurn(Role role, String text) {

    public enum Role { USER, ASSISTANT, SYSTEM }

    public static LlmTurn user(String text) {
        return new LlmTurn(Role.USER, text);
    }

    public static LlmTurn assistant(String text) {
        return new LlmTurn(Role.ASSISTANT, text);
    }

    public static LlmTurn system(String text) {
        return new LlmTurn(Role.SYSTEM, text);
    }
}
