package com.alimtalk.llm;

import java.time.Duration;
import java.util.List;

public interface LlmClient {

    /** 대화 턴을 순서대로 보낸다. timeout 안에 응답을 받지 못하면 LlmException(TIMEOUT). */
    LlmResponse generate(String system, List<LlmTurn> turns, Duration timeout) throws LlmException;

    /** 한 문장 요청(사용자 턴 1개). */
    default LlmResponse generate(String system, String user, Duration timeout) throws LlmException {
        return generate(system, List.of(LlmTurn.user(user)), timeout);
    }
}
