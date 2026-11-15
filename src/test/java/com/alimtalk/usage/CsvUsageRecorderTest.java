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

    private static UsageRecord recordAt(String instant, String result, int attempts, LlmUsage usage, double cost, String prompt) {
        return new UsageRecord(Instant.parse(instant), result, attempts, 1000, usage, cost, "claude-opus-5-5", "medium",
                "002001", "BA", "NONE", prompt, "NEW", 1);
    }

    @Test
    void summaryFileNameAddsSuffix() {
        assertThat(CsvUsageRecorder.summaryPathOf(Path.of("logs/llm-usage-v2.csv")))
                .isEqualTo(Path.of("logs/llm-usage-v2-summary.csv"));
        assertThat(CsvUsageRecorder.summaryPathOf(Path.of("usage"))).isEqualTo(Path.of("usage-summary.csv"));
    }

    @Test
    void writesDailySummaryWithTotal() throws Exception {
        Path file = dir.resolve("logs/u.csv");
        CsvUsageRecorder recorder = new CsvUsageRecorder(file, SEOUL);

        // 서울 기준: 11-13 두 건, 11-14 세 건
        recorder.record(recordAt("2026-11-12T23:00:00Z", "SUCCESS", 1, new LlmUsage(10, 100, 1000, 0), 0.01, "a"));
        recorder.record(recordAt("2026-11-13T01:00:00Z", "ASK", 1, new LlmUsage(20, 200, 1000, 0), 0.02, "b"));
        recorder.record(recordAt("2026-11-14T02:00:00Z", "RULE_VIOLATION", 3, new LlmUsage(30, 300, 0, 500), 0.03, "c"));
        recorder.record(recordAt("2026-11-14T03:00:00Z", "AD_CONTENT", 1, LlmUsage.ZERO, 0.0, "d"));
        recorder.record(recordAt("2026-11-14T04:00:00Z", "ERROR_TIMEOUT", 1, LlmUsage.ZERO, 0.0, "e"));

        Path summary = dir.resolve("logs/u-summary.csv");
        assertThat(Files.readAllBytes(summary)[0] & 0xFF).isEqualTo(0xEF);
        assertThat(lines(summary)).containsExactly(
                "﻿날짜,요청수,SUCCESS,ASK,RULE_VIOLATION,AD_CONTENT,기타,시도,입력토큰,출력토큰,캐시읽기,캐시저장,추정비용(USD)",
                "2026-11-13,2,1,1,0,0,0,2,30,300,2000,0,0.030000",
                "2026-11-14,3,0,0,1,1,1,5,30,300,0,500,0.030000",
                "합계,5,1,1,1,1,1,7,60,600,2000,500,0.060000");
    }

    @Test
    void summaryHandlesQuotedPromptAndSkipsBrokenLines() throws Exception {
        Path file = dir.resolve("u.csv");
        CsvUsageRecorder recorder = new CsvUsageRecorder(file, SEOUL);
        recorder.record(recordAt("2026-11-12T23:00:00Z", "SUCCESS", 1, new LlmUsage(1, 2, 3, 4), 0.5, "주문, \"취소\"\n안내"));
        Files.writeString(file, "깨진 줄,SUCCESS,하나,x\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        recorder.record(recordAt("2026-11-12T23:30:00Z", "SUCCESS", 1, new LlmUsage(1, 2, 3, 4), 0.5, "x"));

        assertThat(lines(dir.resolve("u-summary.csv"))).containsExactly(
                "﻿날짜,요청수,SUCCESS,ASK,RULE_VIOLATION,AD_CONTENT,기타,시도,입력토큰,출력토큰,캐시읽기,캐시저장,추정비용(USD)",
                "2026-11-13,2,2,0,0,0,0,2,2,4,6,8,1.000000",
                "합계,2,2,0,0,0,0,2,2,4,6,8,1.000000");
    }

    @Test
    void summaryFailureKeepsMainRecord() throws Exception {
        Path file = dir.resolve("u.csv");
        // 요약 파일 자리에 비어 있지 않은 폴더 → 요약 쓰기 실패
        Files.writeString(Files.createDirectory(dir.resolve("u-summary.csv")).resolve("keep"), "x");

        new CsvUsageRecorder(file, SEOUL).record(record("x"));

        assertThat(lines(file)).hasSize(2);
    }

    @TempDir
    Path dir;
}
