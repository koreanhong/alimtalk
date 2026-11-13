package com.alimtalk.llm;

import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * API 키 없이 화면을 확인하기 위한 가짜 LLM (프로필 fake-llm). 마지막 사용자 발화("요청: " 뒤)로 응답을 정한다.
 * <ul>
 *   <li>"광고"·"홍보" → 광고 거절(AD_CONTENT)</li>
 *   <li>"다듬"·"정중" → 다듬기(POLISH) 결과</li>
 *   <li>"생성해줘" → 질문 "어떤 유형의 메시지인가요?" + 선택지</li>
 *   <li>첫 발화가 "... 안내 메시지"(시작 선택지) → 질문 "어떤 정보를 넣을까요?" + 선택지</li>
 *   <li>"위반" → 1,100자 본문(규칙 위반)</li>
 *   <li>그 외 → 주문완료 템플릿(NEW)</li>
 * </ul>
 */
@Component
@Profile("fake-llm")
public class FakeLlmClient implements LlmClient {

    @Override
    public LlmResponse generate(String system, List<LlmTurn> turns, Duration timeout) {
        List<LlmTurn> users = turns.stream().filter(t -> t.role() == LlmTurn.Role.USER).toList();
        String last = users.isEmpty() ? "" : users.get(users.size() - 1).text();
        return new LlmResponse(answer(request(last), users.size()), LlmUsage.ZERO);
    }

    /** 첫 발화에는 채널·현재 폼 문맥이 앞에 붙으므로 마지막 "요청: " 뒤만 본다. */
    private static String request(String text) {
        int i = text.lastIndexOf("요청: ");
        return i < 0 ? text : text.substring(i + "요청: ".length());
    }

    private LlmOutput answer(String request, int userTurns) {
        if (request.contains("광고") || request.contains("홍보")) {
            return new LlmOutput(LlmOutput.Status.AD_CONTENT, LlmOutput.Mode.NEW,
                    "알림톡으로는 광고를 보낼 수 없습니다. 브랜드 메시지를 이용해 주세요.", List.of(), null);
        }
        if (request.contains("다듬") || request.contains("정중")) {
            return new LlmOutput(LlmOutput.Status.OK, LlmOutput.Mode.POLISH, "항목별로 정리했습니다.", List.of("더 짧게"),
                    draft("[주문완료 안내]\n안녕하세요 #{고객명}님.\n주문이 정상적으로 접수되었습니다.\n\n▶ 주문번호: #{주문번호}\n▶ 상품명: #{상품명}"));
        }
        if (request.contains("생성해줘")) {
            return ask("어떤 유형의 메시지인가요?", "배송 안내", "인증번호", "주문 완료", "예약 확정", "회원가입");
        }
        if (userTurns == 1 && request.strip().endsWith("안내 메시지")) {
            return ask("어떤 정보를 넣을까요?", "택배사·송장번호", "배송 예정일", "상품명");
        }
        String content = request.contains("위반")
                ? "#{고객명}님, 주문이 접수되었습니다.\n" + "가".repeat(1100)
                : "[주문완료 안내]\n안녕하세요 #{고객명}님.\n주문이 완료되었습니다.\n\n▶ 주문번호: #{주문번호}\n▶ 상품명: #{상품명}";
        return new LlmOutput(LlmOutput.Status.OK, LlmOutput.Mode.NEW, "주문 완료 안내를 작성했습니다.",
                List.of("고객센터 안내 추가", "이대로 완료"), draft(content));
    }

    private static LlmOutput ask(String question, String... choices) {
        return new LlmOutput(LlmOutput.Status.ASK, LlmOutput.Mode.NEW, question, List.of(choices), null);
    }

    private static LlmOutput.DraftOut draft(String content) {
        return new LlmOutput.DraftOut("002001", "주문 완료 안내", "ORDER_COMPLETE_01",
                LlmOutput.MsgType.EX, LlmOutput.Emph.NONE, "", "", content, "문의: 고객센터 1588-0000", false,
                List.of(new LlmOutput.Sample("고객명", "홍길동"),
                        new LlmOutput.Sample("주문번호", "20261112-0001"),
                        new LlmOutput.Sample("상품명", "무선 키보드")));
    }
}
