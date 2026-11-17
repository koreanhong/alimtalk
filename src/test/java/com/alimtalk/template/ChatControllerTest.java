package com.alimtalk.template;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alimtalk.llm.LlmException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TemplateGenerationService service;

    private static final TemplateDraft DRAFT = new TemplateDraft("003001", "배송 시작 안내", "SHIP_START_01",
            "BA", "NONE", "", "", "#{고객명}님, 배송이 시작되었습니다.", "", false, Map.of("고객명", "홍길동"));

    private static final String FORM = "{\"category\":\"002001\",\"name\":\"주문\",\"code\":\"C\",\"msgType\":\"BA\",\"emph\":\"NONE\","
            + "\"title\":\"\",\"sub\":\"\",\"content\":\"본문\",\"extra\":\"\",\"security\":false,\"samples\":{}}";

    private ResultActions post(String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/templates/chat").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String msg(String role, String text) {
        return "{\"role\":\"" + role + "\",\"text\":\"" + text + "\"}";
    }

    private static String body(String messages, String channel, String form) {
        return "{\"messages\":[" + messages + "],\"channel\":\"" + channel + "\"" + (form == null ? "" : ",\"form\":" + form) + "}";
    }

    @Test
    void askResponse() throws Exception {
        when(service.chat(anyList(), any(), anyString()))
                .thenReturn(new GenerationResult.Asked("어떤 정보를 넣을까요?", List.of("택배사·송장번호", "상품명")));

        post(body(msg("user", "배송 시작 안내 메시지"), "@mystore", FORM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ASK"))
                .andExpect(jsonPath("$.reply.text").value("어떤 정보를 넣을까요?"))
                .andExpect(jsonPath("$.reply.choices[1]").value("상품명"))
                .andExpect(jsonPath("$.draft").doesNotExist());
    }

    @Test
    void draftResponseWithModeAndChoices() throws Exception {
        when(service.chat(anyList(), any(), anyString())).thenReturn(new GenerationResult.Success(DRAFT, List.of("경고"), 1,
                "POLISH", "항목별로 정리했습니다.", List.of("더 짧게")));

        post(body(msg("user", "다듬어줘"), "@mystore", FORM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DRAFT"))
                .andExpect(jsonPath("$.mode").value("POLISH"))
                .andExpect(jsonPath("$.reply.text").value("항목별로 정리했습니다."))
                .andExpect(jsonPath("$.reply.choices[0]").value("더 짧게"))
                .andExpect(jsonPath("$.draft.content").value(DRAFT.content()))
                .andExpect(jsonPath("$.warnings[0]").value("경고"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.attempts").value(1));
    }

    @Test
    void ruleViolationIsDraftWithErrors200() throws Exception {
        when(service.chat(anyList(), any(), anyString())).thenReturn(new GenerationResult.RuleViolation(DRAFT,
                List.of("본문이 1,020자입니다"), List.of(), 3, "NEW", "", List.of()));

        post(body(msg("user", "이용약관 전부"), "@mystore", FORM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DRAFT"))
                .andExpect(jsonPath("$.errors[0]").value("본문이 1,020자입니다"))
                .andExpect(jsonPath("$.draft.content").value(DRAFT.content()));
    }

    @Test
    void rejectedResponse() throws Exception {
        when(service.chat(anyList(), any(), anyString()))
                .thenReturn(new GenerationResult.Rejected("AD_CONTENT", "광고는 알림톡으로 보낼 수 없습니다."));

        post(body(msg("user", "할인 행사 홍보"), "@mystore", FORM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("REJECTED"))
                .andExpect(jsonPath("$.reply.text").value("광고는 알림톡으로 보낼 수 없습니다."));
    }

    @Test
    void passesTurnsWithKindAndChannelName() throws Exception {
        when(service.chat(anyList(), any(), anyString())).thenReturn(new GenerationResult.Asked("q", List.of()));
        String messages = msg("user", "배송 시작 안내 메시지") + ",{\"role\":\"assistant\",\"text\":\"q1\",\"kind\":\"ASK\"},"
                + msg("user", "상품명");

        post(body(messages, "@mystore", FORM)).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurnInput>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).chat(captor.capture(), any(), eq("아디다스"));
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).containsExactly(
                new ChatTurnInput("user", "배송 시작 안내 메시지", null),
                new ChatTurnInput("assistant", "q1", "ASK"),
                new ChatTurnInput("user", "상품명", null));
    }

    @Test
    void emptyMessagesIs400() throws Exception {
        post(body("", "@mystore", FORM)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("BAD_REQUEST"));
        verify(service, never()).chat(any(), any(), any());
    }

    @Test
    void twentyOneMessagesIs400() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) sb.append(',');
            sb.append(msg(i % 2 == 0 ? "user" : "assistant", "m" + i));
        }
        post(body(sb.toString(), "@mystore", FORM)).andExpect(status().isBadRequest());
        verify(service, never()).chat(any(), any(), any());
    }

    @Test
    void tooLongTextIs400() throws Exception {
        post(body(msg("user", "가".repeat(501)), "@mystore", FORM)).andExpect(status().isBadRequest());
        verify(service, never()).chat(any(), any(), any());
    }

    @Test
    void blankTextIs400() throws Exception {
        post(body(msg("user", "  "), "@mystore", FORM)).andExpect(status().isBadRequest());
        verify(service, never()).chat(any(), any(), any());
    }

    @Test
    void lastMessageMustBeUser400() throws Exception {
        post(body(msg("user", "a") + "," + msg("assistant", "b"), "@mystore", FORM))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("BAD_REQUEST"));
        verify(service, never()).chat(any(), any(), any());
    }

    @Test
    void badRoleIs400() throws Exception {
        post(body(msg("system", "a"), "@mystore", FORM)).andExpect(status().isBadRequest());
        verify(service, never()).chat(any(), any(), any());
    }

    @Test
    void nullFormTreatedAsEmpty() throws Exception {
        when(service.chat(anyList(), any(), anyString())).thenReturn(new GenerationResult.Asked("q", List.of()));

        post(body(msg("user", "배송 안내"), "@mystore", null)).andExpect(status().isOk());

        ArgumentCaptor<TemplateDraft> captor = ArgumentCaptor.forClass(TemplateDraft.class);
        verify(service).chat(anyList(), captor.capture(), anyString());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().content()).isEmpty();
    }

    @Test
    void unknownChannelBlank() throws Exception {
        when(service.chat(anyList(), any(), anyString())).thenReturn(new GenerationResult.Asked("q", List.of()));

        post(body(msg("user", "배송 안내"), "아무거나", FORM)).andExpect(status().isOk());

        verify(service).chat(anyList(), any(), eq(""));
    }

    // ---------- 이상한 요청·폼 크기 제한 ----------

    private static String form(String name, String content, String extra, String samples) {
        return "{\"category\":\"002001\",\"name\":\"" + name + "\",\"code\":\"C\",\"msgType\":\"EX\",\"emph\":\"NONE\","
                + "\"title\":\"\",\"sub\":\"\",\"content\":\"" + content + "\",\"extra\":\"" + extra + "\",\"security\":false,"
                + "\"samples\":" + samples + "}";
    }

    private static String samples(int count, int keyLen, int valueLen) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            String key = (i + "k" + "가".repeat(keyLen)).substring(0, Math.max(keyLen, 1));
            sb.append('"').append(key).append("\":\"").append("나".repeat(valueLen)).append('"');
        }
        return sb.append('}').toString();
    }

    private void expect400(String body, String messagePart) throws Exception {
        post(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(messagePart)));
        verify(service, never()).chat(any(), any(), any());
    }

    @Test
    void nullMessageIs400() throws Exception {
        expect400("{\"messages\":[null],\"channel\":\"@mystore\"}", "발화");
    }

    @Test
    void formContentOver2000Is400() throws Exception {
        expect400(body(msg("user", "다듬어줘"), "@mystore", form("주문", "가".repeat(2001), "", "{}")), "본문");
    }

    @Test
    void formExtraOver2000Is400() throws Exception {
        expect400(body(msg("user", "다듬어줘"), "@mystore", form("주문", "본문", "가".repeat(2001), "{}")), "부가정보");
    }

    @Test
    void formShortFieldOver200Is400() throws Exception {
        expect400(body(msg("user", "다듬어줘"), "@mystore", form("가".repeat(201), "본문", "", "{}")), "이름");
    }

    @Test
    void formTooManySamplesIs400() throws Exception {
        expect400(body(msg("user", "다듬어줘"), "@mystore", form("주문", "본문", "", samples(61, 5, 5))), "예시값");
    }

    @Test
    void formSampleKeyOver100Is400() throws Exception {
        expect400(body(msg("user", "다듬어줘"), "@mystore", form("주문", "본문", "", samples(1, 101, 5))), "예시값");
    }

    @Test
    void formSampleValueOver100Is400() throws Exception {
        expect400(body(msg("user", "다듬어줘"), "@mystore", form("주문", "본문", "", samples(1, 5, 101))), "예시값");
    }

    @Test
    void formAtLimitsIsAccepted() throws Exception {
        when(service.chat(anyList(), any(), anyString())).thenReturn(new GenerationResult.Asked("q", List.of()));
        String f = form("가".repeat(200), "가".repeat(2000), "가".repeat(2000), samples(60, 100, 100));

        post(body(msg("user", "다듬어줘"), "@mystore", f)).andExpect(status().isOk());
    }

    @Test
    void formWithNullTypesIsAccepted() throws Exception {
        when(service.chat(anyList(), any(), anyString())).thenReturn(new GenerationResult.Asked("q", List.of()));
        String f = "{\"category\":\"002001\",\"name\":\"주문\",\"code\":\"C\",\"msgType\":null,\"emph\":null,"
                + "\"content\":\"본문\",\"samples\":null}";

        post(body(msg("user", "다듬어줘"), "@mystore", f)).andExpect(status().isOk());
    }

    @Test
    void timeoutIs504() throws Exception {
        when(service.chat(anyList(), any(), anyString())).thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "t"));

        post(body(msg("user", "배송 안내"), "@mystore", FORM))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.reason").value("TIMEOUT"));
    }

    // ---------- 아이템리스트(설계 12.3) ----------

    private static String itemForm(String item) {
        return "{\"category\":\"002001\",\"name\":\"주문\",\"code\":\"C\",\"msgType\":\"BA\",\"emph\":\"ITEM_LIST\","
                + "\"content\":\"본문\",\"samples\":{},\"item\":" + item + "}";
    }

    private static String rows(int count, int len) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"항목\",\"desc\":\"").append("가".repeat(len)).append("\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    void formItemIsPassedToService() throws Exception {
        when(service.chat(anyList(), any(), anyString())).thenReturn(new GenerationResult.Asked("q", List.of()));
        String item = "{\"header\":\"주문 내역\",\"hlTitle\":\"\",\"hlDesc\":\"\",\"list\":" + rows(2, 3)
                + ",\"sumName\":\"합계\",\"sumDesc\":\"1,000원\"}";

        post(body(msg("user", "다듬어줘"), "@mystore", itemForm(item))).andExpect(status().isOk());

        org.mockito.ArgumentCaptor<TemplateDraft> form = org.mockito.ArgumentCaptor.forClass(TemplateDraft.class);
        verify(service).chat(anyList(), form.capture(), anyString());
        org.assertj.core.api.Assertions.assertThat(form.getValue().item().header()).isEqualTo("주문 내역");
        org.assertj.core.api.Assertions.assertThat(form.getValue().item().list()).hasSize(2);
    }

    @Test
    void formItemTooManyRowsIs400() throws Exception {
        String item = "{\"header\":\"h\",\"list\":" + rows(11, 1) + "}";
        expect400(body(msg("user", "다듬어줘"), "@mystore", itemForm(item)), "아이템리스트");
    }

    @Test
    void formItemFieldOver200Is400() throws Exception {
        expect400(body(msg("user", "다듬어줘"), "@mystore", itemForm("{\"header\":\"" + "가".repeat(201) + "\"}")), "아이템리스트");
        expect400(body(msg("user", "다듬어줘"), "@mystore", itemForm("{\"list\":" + rows(2, 201) + "}")), "아이템리스트");
    }

    @Test
    void draftResponseIncludesItem() throws Exception {
        TemplateDraft d = new TemplateDraft("002001", "주문 완료", "C", "BA", "ITEM_LIST", "", "", "본문", "", false, Map.of(),
                new TemplateDraft.Item("주문 내역", "", "", List.of(new TemplateDraft.Row("상품명", "키보드")), "", ""));
        when(service.chat(anyList(), any(), anyString())).thenReturn(
                new GenerationResult.Success(d, List.of(), 1, "NEW", "작성했습니다.", List.of()));

        post(body(msg("user", "아이템리스트로 주문 안내"), "@mystore", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.item.header").value("주문 내역"))
                .andExpect(jsonPath("$.draft.item.list[0].name").value("상품명"));
    }
}
