package com.alimtalk.template;

import com.alimtalk.llm.LlmException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 대화형 세팅 1턴. 규칙 위반도 대화의 한 응답으로 보여 주기 위해 200 + errors로 응답한다. */
@RestController
public class ChatController {

    private static final TemplateDraft EMPTY_FORM = new TemplateDraft("", "", "", "", "", "", "", "", "", false, Map.of());

    private final TemplateGenerationService service;

    public ChatController(TemplateGenerationService service) {
        this.service = service;
    }

    @PostMapping("/api/templates/chat")
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequest req) throws LlmException {
        List<ChatRequest.Msg> messages = req.messages();
        if (!"user".equals(messages.get(messages.size() - 1).role())) {
            return ResponseEntity.badRequest().body(TemplateController.ErrorBody.of("BAD_REQUEST", "마지막 발화는 사용자 발화여야 합니다."));
        }
        List<ChatTurnInput> turns = messages.stream()
                .map(m -> new ChatTurnInput(m.role(), m.text().strip(), m.kind()))
                .toList();
        TemplateDraft form = req.form() == null ? EMPTY_FORM : req.form();
        GenerationResult result = service.chat(turns, form, Channels.displayName(req.channel()));
        return ResponseEntity.ok(toBody(result));
    }

    private static ChatResponse toBody(GenerationResult result) {
        return switch (result) {
            case GenerationResult.Asked a -> new ChatResponse("ASK", null, new Reply(a.question(), a.choices()),
                    null, null, null, null);
            case GenerationResult.Success s -> new ChatResponse("DRAFT", s.mode(), new Reply(s.summary(), s.choices()),
                    s.draft(), s.warnings(), List.of(), s.attempts());
            case GenerationResult.RuleViolation v -> new ChatResponse("DRAFT", v.mode(), new Reply(v.summary(), v.choices()),
                    v.draft(), v.warnings(), v.errors(), v.attempts());
            case GenerationResult.Rejected r -> new ChatResponse("REJECTED", null, new Reply(r.message(), List.of()),
                    null, null, null, null);
        };
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatResponse(String kind, String mode, Reply reply, TemplateDraft draft,
                        List<String> warnings, List<String> errors, Integer attempts) {}

    record Reply(String text, List<String> choices) {}
}
