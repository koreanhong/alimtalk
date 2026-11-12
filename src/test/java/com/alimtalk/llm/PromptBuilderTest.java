package com.alimtalk.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.alimtalk.rules.RuleLoader;
import com.alimtalk.template.TemplateDraft;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder(new RuleLoader());

    @Test
    void systemContainsReviewGuideAndTargets() {
        String system = builder.system();

        assertThat(system).contains("# 알림톡 템플릿 작성 지침");
        assertThat(system).contains("700");
        assertThat(system).contains("002001");
        assertThat(system).doesNotContain("1000").doesNotContain("1,000");
    }

    @Test
    void systemIsStableAcrossCalls() {
        assertThat(builder.system()).isEqualTo(builder.system());
    }

    @Test
    void retryIncludesErrorsAndPrevious() {
        TemplateDraft previous = new TemplateDraft("002001", "주문 완료 안내", "ORDER_COMPLETE_01", "BA", "NONE",
                "", "", "#{고객명}님, 이전 본문입니다.", "", false, Map.of("고객명", "홍길동"));
        List<String> errors = List.of("본문이 1,050자입니다 (최대 1,000자). 50자 이상 줄여야 합니다.", "부가정보에는 변수를 쓸 수 없습니다.");

        String retry = builder.retry("주문 관련 메시지 세팅해줘", "마이스토어", previous, errors);

        assertThat(retry).contains("주문 관련 메시지 세팅해줘");
        assertThat(retry).contains("#{고객명}님, 이전 본문입니다.");
        assertThat(retry).contains(errors.get(0)).contains(errors.get(1));
    }
}
