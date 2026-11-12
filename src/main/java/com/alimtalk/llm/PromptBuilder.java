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
        String channelLine = channelName == null || channelName.isBlank() ? "" : "발신 채널: " + channelName + "\n";
        return channelLine + "요청: " + prompt;
    }

    public String retry(String prompt, String channelName, TemplateDraft previous, List<String> errors) {
        return user(prompt, channelName)
                + "\n\n아래는 직전에 작성한 템플릿입니다.\n" + describe(previous)
                + "\n\n이 템플릿은 다음 규칙을 어겼습니다. 모두 고쳐서 다시 작성하세요.\n"
                + errors.stream().map(e -> "- " + e).collect(Collectors.joining("\n"));
    }

    private static String buildSystem(String guide, RuleSet rules) {
        RuleSet.Limits lim = rules.limits();
        String categories = rules.categories().stream()
                .map(c -> "- " + c + ": " + CATEGORY_NAMES.getOrDefault(c, c))
                .collect(Collectors.joining("\n"));
        return guide
                + "\n\n## 5. 형식 목표 (반드시 지킨다)\n"
                + "- 강조 제목·보조 문구·본문·부가정보를 합친 길이: 변수를 예시값으로 바꿨을 때 " + rules.promptLengthTarget() + "자 이내\n"
                + "- 부가정보: " + target(lim.extra()) + "자 이내\n"
                + "- 강조 제목·보조 문구: 각각 " + target(lim.title()) + "자 이내\n"
                + "- 템플릿 이름: " + target(lim.name()) + "자 이내\n"
                + "- 템플릿 코드: 정규식 " + rules.codePattern() + "\n"
                + "- 변수: " + target(lim.vars()) + "개 이하\n"
                + "- 메시지 유형: " + String.join(", ", rules.msgTypes()) + " / 강조 유형: " + String.join(", ", rules.emphTypes()) + "\n"
                + "- 쓰지 않는 표현: " + String.join(", ", rules.bannedPhrases()) + "\n"
                + "\n## 6. 카테고리 코드 (이 중 하나만 쓴다)\n" + categories + "\n";
    }

    /** 실제 제한의 70%를 목표로 준다. */
    private static int target(int limit) {
        return limit * 7 / 10;
    }

    private static String describe(TemplateDraft d) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 카테고리: ").append(d.category()).append('\n')
                .append("- 이름: ").append(d.name()).append('\n')
                .append("- 코드: ").append(d.code()).append('\n')
                .append("- 메시지 유형: ").append(d.msgType()).append('\n')
                .append("- 강조 유형: ").append(d.emph()).append('\n');
        if ("TEXT".equals(d.emph())) {
            sb.append("- 강조 제목: ").append(d.title()).append('\n')
                    .append("- 보조 문구: ").append(d.sub()).append('\n');
        }
        sb.append("- 본문:\n").append(d.content()).append('\n');
        if ("EX".equals(d.msgType())) {
            sb.append("- 부가정보:\n").append(d.extra()).append('\n');
        }
        if (d.samples() != null && !d.samples().isEmpty()) {
            sb.append("- 예시값: ").append(d.samples()).append('\n');
        }
        return sb.toString();
    }
}
