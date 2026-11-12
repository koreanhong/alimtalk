package com.alimtalk.usage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 사용량을 CSV 파일에 한 줄씩 추가한다. 파일을 처음 만들 때 엑셀 한글 깨짐을 막는 UTF-8 BOM과 제목 줄을 쓴다.
 * 쓰기에 실패해도 예외를 던지지 않는다(생성 요청 응답에 영향을 주지 않기 위해).
 */
public class CsvUsageRecorder implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(CsvUsageRecorder.class);
    private static final String BOM = "﻿";
    private static final String HEADER = String.join(",", "시각", "결과", "시도", "소요(ms)",
            "입력토큰", "출력토큰", "캐시읽기", "캐시저장", "추정비용(USD)", "모델", "effort",
            "카테고리", "메시지유형", "강조유형", "요청문장");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path path;
    private final ZoneId zone;

    public CsvUsageRecorder(Path path, ZoneId zone) {
        this.path = path;
        this.zone = zone;
    }

    @Override
    public synchronized void record(UsageRecord r) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            StringBuilder out = new StringBuilder();
            if (!Files.exists(path) || Files.size(path) == 0) {
                out.append(BOM).append(HEADER).append('\n');
            }
            out.append(line(r)).append('\n');
            Files.writeString(path, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("사용량 기록 실패({}): {}", path, e.getClass().getSimpleName());
        }
    }

    private String line(UsageRecord r) {
        List<String> fields = List.of(
                TIME.format(r.at().atZone(zone)),
                nz(r.result()),
                String.valueOf(r.attempts()),
                String.valueOf(r.elapsedMs()),
                String.valueOf(r.usage().input()),
                String.valueOf(r.usage().output()),
                String.valueOf(r.usage().cacheRead()),
                String.valueOf(r.usage().cacheWrite()),
                String.format(Locale.ROOT, "%.6f", r.costUsd()),
                nz(r.model()),
                nz(r.effort()),
                nz(r.category()),
                nz(r.msgType()),
                nz(r.emph()),
                nz(r.prompt()));
        return fields.stream().map(CsvUsageRecorder::escape).collect(Collectors.joining(","));
    }

    /** 쉼표·따옴표·줄바꿈이 있으면 큰따옴표로 감싸고 내부 따옴표는 두 번 쓴다(RFC 4180). */
    private static String escape(String v) {
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
