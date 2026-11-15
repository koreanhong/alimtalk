package com.alimtalk.usage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 사용량을 CSV 파일에 한 줄씩 추가한다. 파일을 처음 만들 때 엑셀 한글 깨짐을 막는 UTF-8 BOM과 제목 줄을 쓴다.
 * 기록할 때마다 같은 폴더의 요약 파일(이름-summary.csv)에 날짜별 합계를 다시 쓴다.
 * 쓰기에 실패해도 예외를 던지지 않는다(생성 요청 응답에 영향을 주지 않기 위해).
 */
public class CsvUsageRecorder implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(CsvUsageRecorder.class);
    private static final String BOM = "﻿";
    private static final String HEADER = String.join(",", "시각", "결과", "시도", "소요(ms)",
            "입력토큰", "출력토큰", "캐시읽기", "캐시저장", "추정비용(USD)", "모델", "effort",
            "카테고리", "메시지유형", "강조유형", "요청문장", "모드", "대화턴수");
    private static final String SUMMARY_HEADER = String.join(",", "날짜", "요청수", "SUCCESS", "ASK",
            "RULE_VIOLATION", "AD_CONTENT", "기타", "시도", "입력토큰", "출력토큰", "캐시읽기", "캐시저장", "추정비용(USD)");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path path;
    private final Path summaryPath;
    private final ZoneId zone;

    public CsvUsageRecorder(Path path, ZoneId zone) {
        this.path = path;
        this.summaryPath = summaryPathOf(path);
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
            return;
        }
        try {
            writeSummary();
        } catch (IOException | RuntimeException e) {
            log.warn("사용량 요약 기록 실패({}): {}", summaryPath, e.getClass().getSimpleName());
        }
    }

    /** 요약 파일 경로: 원본 이름(.csv 제외)에 -summary.csv를 붙여 같은 폴더에 둔다. */
    static Path summaryPathOf(Path main) {
        String name = main.getFileName().toString();
        String base = name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
        return main.resolveSibling(base + "-summary.csv");
    }

    /**
     * 원본 CSV 전체를 읽어 날짜별 합계 + 전체 합계를 요약 파일에 다시 쓴다(원본에서 매번 다시 계산하므로 원본을 고쳐도 맞춰진다).
     * 열이 모자라거나 숫자가 아닌 줄은 건너뛴다. 임시 파일에 쓴 뒤 바꿔치기해 쓰다 실패해도 기존 요약이 깨지지 않게 한다.
     */
    private void writeSummary() throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith(BOM)) {
            text = text.substring(BOM.length());
        }
        List<List<String>> rows = parse(text);
        Map<String, DaySum> days = new TreeMap<>();
        DaySum total = new DaySum();
        for (List<String> row : rows.subList(Math.min(1, rows.size()), rows.size())) { // 첫 줄은 제목
            DaySum one = DaySum.of(row);
            if (one != null) {
                days.computeIfAbsent(row.get(0).substring(0, 10), k -> new DaySum()).add(one);
                total.add(one);
            }
        }
        StringBuilder out = new StringBuilder(BOM).append(SUMMARY_HEADER).append('\n');
        days.forEach((day, s) -> out.append(s.line(day)).append('\n'));
        out.append(total.line("합계")).append('\n');

        Path tmp = summaryPath.resolveSibling(summaryPath.getFileName() + ".tmp");
        Files.writeString(tmp, out, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, summaryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, summaryPath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** RFC 4180 CSV를 줄(레코드)·칸으로 나눈다. 큰따옴표 안의 쉼표·줄바꿈·"" 처리. */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** 하루(또는 전체) 합계. */
    private static final class DaySum {
        private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}.*");

        long count, success, ask, violation, ad, other, attempts, input, output, cacheRead, cacheWrite;
        double cost;

        /** 원본 한 줄을 1건짜리 합계로. 형식이 맞지 않으면 null. */
        static DaySum of(List<String> row) {
            if (row.size() < 9 || !DATE.matcher(row.get(0)).matches()) {
                return null;
            }
            try {
                DaySum s = new DaySum();
                s.count = 1;
                switch (row.get(1)) {
                    case "SUCCESS" -> s.success = 1;
                    case "ASK" -> s.ask = 1;
                    case "RULE_VIOLATION" -> s.violation = 1;
                    case "AD_CONTENT" -> s.ad = 1;
                    default -> s.other = 1;
                }
                s.attempts = Long.parseLong(row.get(2));
                s.input = Long.parseLong(row.get(4));
                s.output = Long.parseLong(row.get(5));
                s.cacheRead = Long.parseLong(row.get(6));
                s.cacheWrite = Long.parseLong(row.get(7));
                s.cost = Double.parseDouble(row.get(8));
                return s;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        void add(DaySum o) {
            count += o.count;
            success += o.success;
            ask += o.ask;
            violation += o.violation;
            ad += o.ad;
            other += o.other;
            attempts += o.attempts;
            input += o.input;
            output += o.output;
            cacheRead += o.cacheRead;
            cacheWrite += o.cacheWrite;
            cost += o.cost;
        }

        String line(String label) {
            return escape(label) + "," + Stream.of(count, success, ask, violation, ad, other, attempts,
                            input, output, cacheRead, cacheWrite).map(String::valueOf).collect(Collectors.joining(","))
                    + "," + String.format(Locale.ROOT, "%.6f", cost);
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
                nz(r.prompt()),
                nz(r.mode()),
                String.valueOf(r.turns()));
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
