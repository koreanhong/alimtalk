package com.alimtalk.llm;

import com.alimtalk.rules.RuleLoader;
import com.alimtalk.rules.RuleSet;
import com.alimtalk.template.TemplateDraft;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Claude에 보낼 프롬프트를 만든다.
 * 시스템 프롬프트는 요청마다 같아야 캐시가 유지되므로 생성 시 1회만 만든다.
 * 글자 수는 LLM이 정확히 세지 못하므로 실제 제한보다 여유 있는 목표치를 준다(실제 판정은 RuleValidator).
 */
@Component
public class PromptBuilder {

    private static final Map<String, String> CATEGORY_NAMES = Map.of(
            "002001", "구매 · 구매완료",
            "002002", "구매 · 구매취소",
            "003001", "배송 · 배송상태",
            "004001", "예약 · 예약완료",
            "006001", "회원 · 회원가입",
            "006003", "회원 · 인증",
            "999999", "기타");

    private final String system;

    public PromptBuilder(RuleLoader loader) {
        this.system = buildSystem(loader.reviewGuide(), loader.ruleSet());
    }

    public String system() {
        return system;
    }

    public String user(String prompt, String channelName) {
        return channelLine(channelName) + "요청: " + prompt;
    }

    /** 질문이 연속 3번이면 대화 끝에 붙이는 운영 지시. */
    public static final String FORCE_DRAFT = "이미 여러 번 질문했다. 이번에는 질문하지 말고 지금까지의 정보로 템플릿을 작성하라(status=OK). 모르는 값은 변수로 둔다.";

    /** 사용자 화면의 현재 폼(텍스트 항목만). 매 요청 달라지므로 시스템 프롬프트가 아니라 메시지에 넣는다. */
    public String formContext(TemplateDraft form) {
        if (form == null || isEmpty(form)) {
            return "현재 폼: 비어 있음";
        }
        String keepExtra = "EX".equals(form.msgType()) || "MI".equals(form.msgType())
                ? "※ 부가정보가 있는 유형이다. 다듬을 때 부가정보를 비우지 말고 유지하며 함께 다듬는다.\n" : "";
        return "현재 폼:\n" + describe(form) + keepExtra;
    }

    private static boolean isEmpty(TemplateDraft d) {
        return blank(d.name()) && blank(d.content()) && blank(d.title()) && blank(d.sub()) && blank(d.extra());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public String retry(String prompt, String channelName, TemplateDraft previous, List<String> errors) {
        return user(prompt, channelName) + "\n\n" + retryNote(previous, errors);
    }

    /** 규칙 위반으로 다시 작성을 요청할 때 덧붙이는 문장(직전 결과 + 위반 목록). */
    public String retryNote(TemplateDraft previous, List<String> errors) {
        return "아래는 직전에 작성한 템플릿입니다.\n" + describe(previous)
                + "\n\n이 템플릿은 다음 규칙을 어겼습니다. 모두 고쳐서 다시 작성하세요.\n"
                + errors.stream().map(e -> "- " + e).collect(Collectors.joining("\n"));
    }

    /** 채널 줄(채널이 있을 때만). */
    public String channelLine(String channelName) {
        return channelName == null || channelName.isBlank() ? "" : "발신 채널: " + channelName + "\n";
    }

    private static String buildSystem(String guide, RuleSet rules) {
        RuleSet.Limits lim = rules.limits();
        String categories = rules.categories().stream()
                .map(c -> "- " + c + ": " + CATEGORY_NAMES.getOrDefault(c, c))
                .collect(Collectors.joining("\n"));
        return guide
                + "\n\n## 6. 형식 목표 (반드시 지킨다)\n"
                + "- 강조 제목·보조 문구·본문·부가정보를 합친 길이: 변수를 예시값으로 바꿨을 때 " + rules.promptLengthTarget() + "자 이내\n"
                + "- 부가정보: " + target(lim.extra()) + "자 이내\n"
                + "- 강조 제목·보조 문구: 각각 " + target(lim.title()) + "자 이내\n"
                + "- 템플릿 이름: " + target(lim.name()) + "자 이내\n"
                + "- 템플릿 코드: 정규식 " + rules.codePattern() + "\n"
                + "- 변수: " + target(lim.vars()) + "개 이하\n"
                + "- 메시지 유형: " + String.join(", ", rules.msgTypes()) + " / 강조 유형: " + String.join(", ", rules.emphTypes()) + "\n"
                + "- 쓰지 않는 표현: " + String.join(", ", rules.bannedPhrases()) + "\n"
                + "\n## 7. 카테고리 코드 (이 중 하나만 쓴다)\n" + categories + "\n";
    }

    /** 실제 제한의 70%를 목표로 준다. */
    private static int target(int limit) {
        return limit * 7 / 10;
    }

    private static String describe(TemplateDraft d) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 카테고리: ").append(nz(d.category())).append('\n')
                .append("- 이름: ").append(nz(d.name())).append('\n')
                .append("- 코드: ").append(nz(d.code())).append('\n')
                .append("- 메시지 유형: ").append(nz(d.msgType())).append('\n')
                .append("- 강조 유형: ").append(nz(d.emph())).append('\n')
                .append("- 보안 템플릿: ").append(d.security() ? "예" : "아니오").append('\n');
        if ("TEXT".equals(d.emph())) {
            sb.append("- 강조 제목: ").append(nz(d.title())).append('\n')
                    .append("- 보조 문구: ").append(nz(d.sub())).append('\n');
        }
        sb.append("- 본문:\n").append(nz(d.content())).append('\n');
        if ("EX".equals(d.msgType()) || "MI".equals(d.msgType())) {
            sb.append("- 부가정보:\n").append(nz(d.extra())).append('\n');
        }
        if (d.samples() != null && !d.samples().isEmpty()) {
            sb.append("- 예시값: ").append(d.samples()).append('\n');
        }
        return sb.toString();
    }

    /** 폼 값이 없으면(null) "null" 글자 대신 빈칸으로 보낸다. */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
