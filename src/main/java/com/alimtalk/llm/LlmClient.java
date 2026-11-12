package com.alimtalk.llm;

import java.time.Duration;

public interface LlmClient {

    /** timeout 안에 응답을 받지 못하면 LlmException(TIMEOUT). */
    LlmOutput generate(String system, String user, Duration timeout) throws LlmException;
}
