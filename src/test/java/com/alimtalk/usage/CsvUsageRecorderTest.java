package com.alimtalk.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.alimtalk.llm.LlmUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvUsageRecorderTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static UsageRecord record(String prompt) {
        return new UsageRecord(Instant.parse("2026-11-12T23:25:31Z"), "SUCCESS", 1, 4100,
                new LlmUsage(41, 343, 5472, 0), 0.0081184, "claude-opus-5-5", "medium",
                "002001", "BA", "NONE", prompt, "NEW", 3);
    }

    private static List<String> lines(Path file) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return List.of(text.split("\r?\n", -1)).stream().filter(l -> !l.isEmpty()).toList();
    }

    @Test
    void writesHeaderOnceWithBomThenOneLinePerRecord() throws Exception {
        Path file = dir.resolve("logs/llm-usage.csv");
        CsvUsageRecorder recorder = new CsvUsageRecorder(file, SEOUL);

        recorder.record(record("주문 관련 메시지 세팅해줘"));
        recorder.record(record("배송 안내"));

        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes[0] & 0xFF).isEqualTo(0xEF); // UTF-8 BOM (엑셀 한글 깨짐 방지)
        List<String> lines = lines(file);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("시각", "결과", "추정비용(USD)", "요청문장").endsWith(",모드,대화턴수");
        assertThat(lines.get(1)).isEqualTo(
                "2026-11-13 08:25:31,SUCCESS,1,4100,41,343,5472,0,0.008118,claude-opus-5-5,medium,002001,BA,NONE,주문 관련 메시지 세팅해줘,NEW,3");
    }

    @Test
    void escapesCommaQuoteAndNewlineInPrompt() throws Exception {
        Path file = dir.resolve("u.csv");
        new CsvUsageRecorder(file, SEOUL).record(record("주문, \"취소\"\n안내"));

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).contains(",\"주문, \"\"취소\"\"\n안내\",NEW,3");
    }

    @Test
    void nullFieldsBecomeEmpty() throws Exception {
        Path file = dir.resolve("u.csv");
        new CsvUsageRecorder(file, SEOUL).record(new UsageRecord(Instant.parse("2026-11-12T23:25:31Z"), "AD_CONTENT", 1, 1900,
                LlmUsage.ZERO, 0, "claude-opus-5-5", "medium", null, null, null, "할인 홍보", null, 1));

        assertThat(lines(file).get(1)).isEqualTo("2026-11-13 08:25:31,AD_CONTENT,1,1900,0,0,0,0,0.000000,claude-opus-5-5,medium,,,,할인 홍보,,1");
    }

    @Test
    void writeFailureDoesNotThrow() throws Exception {
        Path notAFile = Files.createDirectory(dir.resolve("is-a-directory"));

        assertThatCode(() -> new CsvUsageRecorder(notAFile, SEOUL).record(record("x"))).doesNotThrowAnyException();
    }

    @TempDir
    Path dir;
}
