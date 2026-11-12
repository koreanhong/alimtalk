package com.alimtalk.rules;

import com.alimtalk.template.TemplateDraft;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * alimtalk-rules.json 기준으로 템플릿 초안을 점검한다.
 * 오류 메시지는 LLM 재요청에 그대로 붙으므로 수치를 포함한 한 문장으로 쓴다.
 */
@Component
public class RuleValidator {

    private static final Pattern VAR = Pattern.compile("#\\{([^{}]*)\\}");

    private final RuleSet rules;
    private final Pattern codePattern;

    public RuleValidator(RuleSet rules) {
        this.rules = rules;
        this.codePattern = Pattern.compile(rules.codePattern());
    }

    public ValidationResult validate(TemplateDraft d) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        RuleSet.Limits lim = rules.limits();

        String name = nz(d.name());
        String content = nz(d.content());
        String extra = nz(d.extra());
        String title = nz(d.title());
        String sub = nz(d.sub());
        Map<String, String> samples = d.samples() == null ? Map.of() : d.samples();
        boolean isEx = "EX".equals(d.msgType());
        boolean isText = "TEXT".equals(d.emph());

        if (!rules.categories().contains(nz(d.category()))) {
            errors.add("카테고리 코드가 올바르지 않습니다: " + nz(d.category()));
        }
        if (!rules.msgTypes().contains(nz(d.msgType()))) {
            errors.add("메시지 유형은 " + String.join(", ", rules.msgTypes()) + " 중 하나여야 합니다.");
        }
        if (!rules.emphTypes().contains(nz(d.emph()))) {
            errors.add("강조 유형은 " + String.join(", ", rules.emphTypes()) + " 중 하나여야 합니다.");
        }
        int nameLen = len(name);
        if (name.isBlank() || nameLen > lim.name()) {
            errors.add("템플릿 이름은 1~%d자여야 합니다. (현재 %d자)".formatted(lim.name(), nameLen));
        }
        if (!codePattern.matcher(nz(d.code())).matches()) {
            errors.add("템플릿 코드는 영문·숫자·_ 조합 1~%d자여야 합니다.".formatted(lim.code()));
        }

        if (content.isBlank()) {
            errors.add("템플릿 내용을 입력해야 합니다.");
        } else if (VAR.matcher(content).replaceAll("").isBlank()) {
            errors.add("본문이 변수로만 이루어져 있습니다. 고정 문구를 함께 써야 합니다.");
        }

        if (isEx) {
            int extraLen = len(extra);
            if (extra.isBlank() || extraLen > lim.extra()) {
                errors.add("부가정보는 1~%d자여야 합니다. (현재 %d자)".formatted(lim.extra(), extraLen));
            }
            if (!variablesIn(extra).isEmpty()) {
                errors.add("부가정보에는 변수를 쓸 수 없습니다.");
            }
        }
        if (isText) {
            int titleLen = len(title);
            if (title.isBlank() || titleLen > lim.title()) {
                errors.add("강조 제목은 1~%d자여야 합니다. (현재 %d자)".formatted(lim.title(), titleLen));
            }
            int subLen = len(sub);
            if (sub.isBlank() || subLen > lim.sub()) {
                errors.add("강조 보조 문구는 1~%d자여야 합니다. (현재 %d자)".formatted(lim.sub(), subLen));
            }
            if (!variablesIn(sub).isEmpty()) {
                errors.add("강조 보조 문구에는 변수를 쓸 수 없습니다.");
            }
        }

        List<String> vars = Stream.concat(
                        isText ? variablesIn(title).stream() : Stream.empty(),
                        variablesIn(content).stream())
                .distinct()
                .toList();
        if (vars.size() > lim.vars()) {
            errors.add("변수는 최대 %d개까지 쓸 수 있습니다. (현재 %d개)".formatted(lim.vars(), vars.size()));
        }
        if (vars.stream().anyMatch(String::isBlank)) {
            errors.add("빈 변수 #{}가 있습니다.");
        }
        List<String> missing = vars.stream()
                .filter(v -> !v.isBlank())
                .filter(v -> samples.get(v) == null || samples.get(v).isBlank())
                .toList();
        if (!missing.isEmpty()) {
            errors.add("예시값이 없는 변수: " + missing.stream().map(v -> "#{" + v + "}").collect(Collectors.joining(", ")));
        }

        String measured = (isText ? title + sub : "") + content + (isEx ? extra : "");
        int total = len(substitute(measured, samples));
        if (total > lim.content()) {
            errors.add("본문이 %,d자입니다 (강조·부가정보 포함, 예시값 치환 기준 최대 %,d자). %,d자 이상 줄여야 합니다."
                    .formatted(total, lim.content(), total - lim.content()));
        }

        String allText = title + "\n" + sub + "\n" + content + "\n" + extra;
        for (String phrase : rules.bannedPhrases()) {
            if (allText.contains(phrase)) {
                errors.add("광고성 문구 '" + phrase + "'는 쓸 수 없습니다.");
            }
        }
        List<String> cautions = rules.cautionWords().stream().filter(allText::contains).toList();
        if (!cautions.isEmpty()) {
            warnings.add("광고로 보일 수 있는 단어가 있습니다: " + String.join(", ", cautions)
                    + ". 정보성 안내인지 확인해 주세요.");
        }
        if (d.security() && !content.contains("인증")) {
            warnings.add("보안 템플릿은 보통 인증번호 발송용입니다. 용도를 다시 확인해 주세요.");
        }

        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    /** #{이름} 형식 변수를 등장 순서대로, 중복 없이 반환한다. */
    public static List<String> variablesIn(String s) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = VAR.matcher(nz(s));
        while (m.find()) {
            found.add(m.group(1));
        }
        return List.copyOf(found);
    }

    private static String substitute(String s, Map<String, String> samples) {
        return VAR.matcher(s).replaceAll(m -> Matcher.quoteReplacement(
                Objects.requireNonNullElse(samples.get(m.group(1)), m.group())));
    }

    private static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
