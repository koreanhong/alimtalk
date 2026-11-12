package com.alimtalk.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** classpath의 rules/ 파일을 기동 시 1회 읽어 둔다. */
@Component
public class RuleLoader {

    private static final String RULES_PATH = "rules/alimtalk-rules.json";
    private static final String GUIDE_PATH = "rules/alimtalk-review-guide.md";

    private final String rawJson;
    private final RuleSet ruleSet;
    private final String reviewGuide;

    public RuleLoader() {
        this.rawJson = readClasspath(RULES_PATH);
        this.reviewGuide = readClasspath(GUIDE_PATH);
        try {
            this.ruleSet = new ObjectMapper().readValue(rawJson, RuleSet.class);
        } catch (IOException e) {
            throw new IllegalStateException("규칙 파일을 읽을 수 없습니다: " + RULES_PATH, e);
        }
    }

    public RuleSet ruleSet() {
        return ruleSet;
    }

    public String rawJson() {
        return rawJson;
    }

    /** LLM 시스템 프롬프트에 넣을 생성 지침 원문. */
    public String reviewGuide() {
        return reviewGuide;
    }

    static String readClasspath(String path) {
        try (InputStream in = RuleLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 리소스가 없습니다: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
