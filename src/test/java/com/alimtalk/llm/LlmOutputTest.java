package com.alimtalk.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.alimtalk.template.TemplateDraft;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 구조화 출력 스키마가 허용값만 받도록, 상태·유형 필드는 enum이어야 한다(SDK가 enum 스키마로 변환). */
class LlmOutputTest {

    private static Class<?> typeOf(Class<?> record, String name) {
        return Arrays.stream(record.getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .map(RecordComponent::getType)
                .findFirst().orElseThrow();
    }

    private static List<String> constants(Class<?> type) {
        return Arrays.stream(type.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList();
    }

    @Test
    void statusAndTypesAreEnumsWithAllowedValuesOnly() {
        assertThat(constants(typeOf(LlmOutput.class, "status"))).containsExactly("OK", "ASK", "AD_CONTENT");
        assertThat(constants(typeOf(LlmOutput.class, "mode"))).containsExactly("NEW", "POLISH");
        assertThat(constants(typeOf(LlmOutput.DraftOut.class, "msgType"))).containsExactly("BA", "EX");
        assertThat(constants(typeOf(LlmOutput.DraftOut.class, "emph"))).containsExactly("NONE", "TEXT");
    }

    @Test
    void sdkBuildsStructuredOutputSchemaFromRecord() {
        // SDK가 레코드에서 JSON 스키마를 만들고 로컬 검증까지 통과해야 실제 요청을 보낼 수 있다
        org.assertj.core.api.Assertions.assertThatCode(() ->
                com.anthropic.models.messages.StructuredOutputConfig.<LlmOutput>builder()
                        .format(LlmOutput.class)
                        .build())
                .doesNotThrowAnyException();
    }

    @Test
    void choicesIsListOfStrings() throws Exception {
        assertThat(typeOf(LlmOutput.class, "choices")).isEqualTo(List.class);
        var generic = Arrays.stream(LlmOutput.class.getRecordComponents())
                .filter(c -> c.getName().equals("choices")).findFirst().orElseThrow().getGenericType().getTypeName();
        assertThat(generic).isEqualTo("java.util.List<java.lang.String>");
    }

    @Test
    void toDraftKeepsStringContractForScreen() {
        LlmOutput.DraftOut out = new LlmOutput.DraftOut("002001", "주문 완료 안내", "ORDER_COMPLETE_01",
                LlmOutput.MsgType.EX, LlmOutput.Emph.TEXT, "제목", "보조", "본문", "부가", false,
                List.of(new LlmOutput.Sample("고객명", "홍길동")));

        TemplateDraft d = out.toDraft();

        assertThat(d.msgType()).isEqualTo("EX");
        assertThat(d.emph()).isEqualTo("TEXT");
        assertThat(d.samples()).containsEntry("고객명", "홍길동");
    }
}
