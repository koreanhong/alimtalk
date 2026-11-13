package com.alimtalk.llm;

import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * API 키 없이 화면을 확인하기 위한 가짜 LLM (프로필 fake-llm).
 * 요청에 "광고"가 있으면 AD_CONTENT, "위반"이 있으면 1,100자 본문(규칙 위반), 그 외에는 주문완료 템플릿을 돌려준다.
 */
@Component
@Profile("fake-llm")
public class FakeLlmClient implements LlmClient {

    @Override
    public LlmResponse generate(String system, List<LlmTurn> turns, Duration timeout) {
        String lastUser = turns.stream().filter(t -> t.role() == LlmTurn.Role.USER)
                .reduce((first, second) -> second).map(LlmTurn::text).orElse("");
        return new LlmResponse(answer(lastUser), LlmUsage.ZERO);
    }

    private LlmOutput answer(String user) {
        String request = user.lines().filter(l -> l.startsWith("요청: ")).findFirst().orElse(user);
        if (request.contains("광고")) {
            return new LlmOutput(LlmOutput.Status.AD_CONTENT, LlmOutput.Mode.NEW, "알림톡으로는 광고를 보낼 수 없습니다. 브랜드 메시지를 이용해 주세요.", List.of(), null);
        }
        String content = request.contains("위반")
                ? "#{고객명}님, 주문이 접수되었습니다.\n" + "가".repeat(1100)
                : "[주문완료 안내]\n안녕하세요 #{고객명}님.\n주문이 완료되었습니다.\n\n▶ 주문번호: #{주문번호}\n▶ 상품명: #{상품명}";
        return new LlmOutput(LlmOutput.Status.OK, LlmOutput.Mode.NEW, "", List.of(), new LlmOutput.DraftOut("002001", "주문 완료 안내", "ORDER_COMPLETE_01",
                LlmOutput.MsgType.EX, LlmOutput.Emph.NONE, "", "", content, "문의: 고객센터 1588-0000", false,
                List.of(new LlmOutput.Sample("고객명", "홍길동"),
                        new LlmOutput.Sample("주문번호", "20261112-0001"),
                        new LlmOutput.Sample("상품명", "무선 키보드"))));
    }
}
