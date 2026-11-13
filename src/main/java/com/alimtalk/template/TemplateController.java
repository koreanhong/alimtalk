package com.alimtalk.template;

import com.alimtalk.llm.LlmException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TemplateController {

    /** 화면의 발신 채널 선택지와 같다. 채널은 사용자가 고르며 LLM에는 표시명만 전달한다. */
    private static final Map<String, String> CHANNEL_NAMES = Map.of(
            "@mystore", "아디다스",
            "@hotvpn", "PMK",
            "@testchannel", "테스트채널");

    private final TemplateGenerationService service;

    public TemplateController(TemplateGenerationService service) {
        this.service = service;
    }

    @PostMapping("/api/templates/generate")
    public ResponseEntity<?> generate(@Valid @RequestBody GenerateRequest req) throws LlmException {
        // 등록된 채널만 이름을 넘긴다. 임의 문자열이 프롬프트에 들어가지 않도록 미등록 값은 빈 이름으로 처리한다.
        String channelName = req.channel() == null ? "" : CHANNEL_NAMES.getOrDefault(req.channel(), "");
        GenerationResult result = service.generate(req.prompt().strip(), channelName);
        return switch (result) {
            case GenerationResult.Success s -> ResponseEntity.ok(new SuccessBody(s.draft(), s.warnings(), s.attempts()));
            case GenerationResult.Rejected r -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorBody(r.reason(), r.message(), null, null, null));
            case GenerationResult.Asked a -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorBody("UNCLEAR", a.question(), null, null, null));
            case GenerationResult.RuleViolation v -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorBody("RULE_VIOLATION", "규칙을 모두 맞추지 못했습니다.", v.draft(), v.errors(), v.warnings()));
        };
    }

    record SuccessBody(TemplateDraft draft, List<String> warnings, int attempts) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorBody(String reason, String message, TemplateDraft draft, List<String> errors, List<String> warnings) {

        static ErrorBody of(String reason, String message) {
            return new ErrorBody(reason, message, null, null, null);
        }
    }
}
