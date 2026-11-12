package com.alimtalk.template;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alimtalk.llm.LlmException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(TemplateController.class)
class TemplateControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TemplateGenerationService service;

    private static final TemplateDraft DRAFT = new TemplateDraft("002001", "주문 완료 안내", "ORDER_COMPLETE_01",
            "BA", "NONE", "", "", "#{고객명}님, 주문이 접수되었습니다.", "", false, Map.of("고객명", "홍길동"));

    private ResultActions postPrompt(String prompt) throws Exception {
        String body = "{\"prompt\":" + quote(prompt) + ",\"channel\":\"@mystore\"}";
        return mvc.perform(post("/api/templates/generate").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void successReturns200WithDraft() throws Exception {
        when(service.generate("주문 관련 메시지 세팅해줘", "마이스토어"))
                .thenReturn(new GenerationResult.Success(DRAFT, List.of("경고"), 1));

        postPrompt("주문 관련 메시지 세팅해줘")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.msgType").value("BA"))
                .andExpect(jsonPath("$.draft.samples.고객명").value("홍길동"))
                .andExpect(jsonPath("$.warnings[0]").value("경고"))
                .andExpect(jsonPath("$.attempts").value(1));
    }

    @Test
    void ruleViolationReturns422WithDraft() throws Exception {
        when(service.generate(anyString(), anyString()))
                .thenReturn(new GenerationResult.RuleViolation(DRAFT, List.of("본문이 1,020자입니다"), List.of(), 3));

        postPrompt("주문 관련 메시지 세팅해줘")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("규칙을 모두 맞추지 못했습니다."))
                .andExpect(jsonPath("$.draft.content").value(DRAFT.content()))
                .andExpect(jsonPath("$.errors[0]").value("본문이 1,020자입니다"));
    }

    @Test
    void adContentReturns422WithoutDraft() throws Exception {
        when(service.generate(anyString(), anyString()))
                .thenReturn(new GenerationResult.Rejected("AD_CONTENT", "알림톡으로는 광고를 보낼 수 없습니다."));

        postPrompt("할인 쿠폰 홍보 보내줘")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("AD_CONTENT"))
                .andExpect(jsonPath("$.message").value("알림톡으로는 광고를 보낼 수 없습니다."))
                .andExpect(jsonPath("$.draft").doesNotExist());
    }

    @Test
    void blankPromptIs400() throws Exception {
        postPrompt("   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("BAD_REQUEST"));
        verify(service, never()).generate(any(), any());
    }

    @Test
    void tooLongPromptIs400() throws Exception {
        postPrompt("가".repeat(501))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("BAD_REQUEST"));
        verify(service, never()).generate(any(), any());
    }

    @Test
    void unknownChannelIsSentAsBlankName() throws Exception {
        when(service.generate(anyString(), anyString()))
                .thenReturn(new GenerationResult.Success(DRAFT, List.of(), 1));
        String body = "{\"prompt\":\"주문 관련 메시지 세팅해줘\",\"channel\":\"아무 문장이나 넣어도 프롬프트에 들어감\"}";

        mvc.perform(post("/api/templates/generate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(service).generate("주문 관련 메시지 세팅해줘", "");
    }

    @Test
    void tooLongChannelIs400() throws Exception {
        String body = "{\"prompt\":\"주문 관련 메시지 세팅해줘\",\"channel\":\"" + "@".repeat(51) + "\"}";

        mvc.perform(post("/api/templates/generate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("BAD_REQUEST"));
        verify(service, never()).generate(any(), any());
    }

    @Test
    void timeoutIs504() throws Exception {
        when(service.generate(anyString(), anyString()))
                .thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "LLM 응답 시간 초과"));

        postPrompt("주문 관련 메시지 세팅해줘")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.reason").value("TIMEOUT"));
    }

    @Test
    void apiErrorIs502WithoutInternalMessage() throws Exception {
        when(service.generate(anyString(), anyString()))
                .thenThrow(new LlmException(LlmException.Kind.API_ERROR, "INTERNAL-DETAIL-xyz"));

        postPrompt("주문 관련 메시지 세팅해줘")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("LLM_ERROR"))
                .andExpect(content().string(not(containsString("INTERNAL-DETAIL-xyz"))));
    }
}
