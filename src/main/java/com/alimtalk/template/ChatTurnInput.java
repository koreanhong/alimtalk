package com.alimtalk.template;

/**
 * 화면이 보내는 대화 한 턴. role: user | assistant.
 * kind는 assistant 턴이 어떤 응답이었는지(ASK | DRAFT | REJECTED)로, 연속 질문 횟수를 셀 때 쓴다.
 */
public record ChatTurnInput(String role, String text, String kind) {

    public boolean isUser() {
        return "user".equals(role);
    }

    public boolean isAsk() {
        return "assistant".equals(role) && "ASK".equals(kind);
    }
}
