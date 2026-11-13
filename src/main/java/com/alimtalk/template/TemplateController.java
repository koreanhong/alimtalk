package com.alimtalk.template;

import com.alimtalk.llm.LlmException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TemplateController {

    private final TemplateGenerationService service;

    public TemplateController(TemplateGenerationService service) {
        this.service = service;
    }

    @PostMapping("/api/templates/generate")
    public ResponseEntity<?> generate(@Valid @RequestBody GenerateRequest req) throws LlmException {
        String channelName = Channels.displayName(req.channel());
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
