package com.alimtalk.rules;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RulesController {

    private final RuleLoader ruleLoader;

    public RulesController(RuleLoader ruleLoader) {
        this.ruleLoader = ruleLoader;
    }

    /** 화면의 글자 수 카운터가 서버와 같은 제한값을 쓰도록 규칙 JSON 원문을 그대로 내려준다. */
    @GetMapping("/api/rules")
    public ResponseEntity<String> rules() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(ruleLoader.rawJson());
    }
}
