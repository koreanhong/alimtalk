package com.alimtalk.llm;

import com.alimtalk.rules.RuleValidator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * API 키 없이 화면을 확인하기 위한 가짜 LLM (프로필 fake-llm). 마지막 사용자 발화("요청: " 뒤)로 응답을 정한다.
 * <ul>
 *   <li>"광고"·"홍보" → 광고 거절(AD_CONTENT)</li>
 *   <li>"다듬" + "위반" → 다듬기(POLISH) + 1,100자 본문(규칙 위반)</li>
 *   <li>"다듬"·"정중" → 다듬기(POLISH) 결과. 현재 폼이 있으면 폼 본문 앞에 "[다듬음] "을 붙이고
 *       부가정보는 부가정보형(EX) 폼만 폼 값, 그 밖에는 빈 문자열(복합형 폼에서 서버의 부가정보 유지 확인용).
 *       현재 폼이 비어 있으면 고정 초안</li>
 *   <li>"경고" → 주문완료 템플릿(NEW) + 주의 단어 "쿠폰"(서버가 경고를 붙임)</li>
 *   <li>"생성해줘" → 질문 "어떤 유형의 메시지인가요?" + 선택지</li>
 *   <li>첫 발화가 "... 안내 메시지"(시작 선택지) → 질문 "어떤 정보를 넣을까요?" + 선택지</li>
 *   <li>"위반" → 1,100자 본문(규칙 위반)</li>
 *   <li>그 외 → 주문완료 템플릿(NEW)</li>
 * </ul>
 */
@Component
@Profile("fake-llm")
public class FakeLlmClient implements LlmClient {

    private static final String FORM_HEAD = "현재 폼:\n";
    /** PromptBuilder.formContext가 쓰는 항목 머리말 중 여러 줄 값(본문·부가정보)이 끝나는 위치. */
    private static final List<String> BLOCK_ENDS = List.of("\n- 부가정보:\n", "\n- 예시값: ", "\n※ ", "\n\n요청: ");

    @Override
    public LlmResponse generate(String system, List<LlmTurn> turns, Duration timeout) {
        List<LlmTurn> users = turns.stream().filter(t -> t.role() == LlmTurn.Role.USER).toList();
        String last = users.isEmpty() ? "" : users.get(users.size() - 1).text();
        String form = users.stream().map(LlmTurn::text).filter(t -> t.contains(FORM_HEAD)).findFirst().orElse(null);
        return new LlmResponse(answer(request(last), users.size(), form), LlmUsage.ZERO);
    }

    /** 첫 발화에는 채널·현재 폼 문맥이 앞에 붙으므로 마지막 "요청: " 뒤만 본다. */
    private static String request(String text) {
        int i = text.lastIndexOf("요청: ");
        return i < 0 ? text : text.substring(i + "요청: ".length());
    }

    private LlmOutput answer(String request, int userTurns, String formTurn) {
        if (request.contains("광고") || request.contains("홍보")) {
            return new LlmOutput(LlmOutput.Status.AD_CONTENT, LlmOutput.Mode.NEW,
                    "알림톡으로는 광고를 보낼 수 없습니다. 브랜드 메시지를 이용해 주세요.", List.of(), null);
        }
        if (request.contains("다듬") && request.contains("위반")) {
            return new LlmOutput(LlmOutput.Status.OK, LlmOutput.Mode.POLISH, "항목별로 정리했습니다.", List.of("더 짧게"),
                    draft("#{고객명}님, 주문이 접수되었습니다.\n" + "가".repeat(1100)));
        }
        if (request.contains("다듬") || request.contains("정중")) {
            LlmOutput.DraftOut polished = formTurn == null ? null : polishForm(formTurn);
            return new LlmOutput(LlmOutput.Status.OK, LlmOutput.Mode.POLISH, "항목별로 정리했습니다.", List.of("더 짧게"),
                    polished != null ? polished
                            : draft("[주문완료 안내]\n안녕하세요 #{고객명}님.\n주문이 정상적으로 접수되었습니다.\n\n▶ 주문번호: #{주문번호}\n▶ 상품명: #{상품명}"));
        }
        if (request.contains("경고")) {
            return new LlmOutput(LlmOutput.Status.OK, LlmOutput.Mode.NEW, "주문 완료 안내를 작성했습니다.",
                    List.of("이대로 완료"),
                    draft("[주문완료 안내]\n안녕하세요 #{고객명}님.\n주문이 완료되었습니다.\n\n▶ 주문번호: #{주문번호}\n▶ 상품명: #{상품명}\n\n"
                            + "적립된 쿠폰은 마이페이지에서 확인하실 수 있습니다."));
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

    /** 현재 폼 문맥(PromptBuilder.formContext 형식)을 읽어 본문 앞에 "[다듬음] "을 붙인 초안. 본문이 없으면 null. */
    private static LlmOutput.DraftOut polishForm(String formTurn) {
        String ctx = formTurn.substring(formTurn.indexOf(FORM_HEAD));
        String content = block(ctx, "- 본문:\n");
        if (content == null || content.isBlank()) {
            return null;
        }
        String msgType = line(ctx, "- 메시지 유형: ");
        boolean text = "TEXT".equals(line(ctx, "- 강조 유형: "));
        boolean ex = "EX".equals(msgType);
        String polished = "[다듬음] " + content;
        Map<String, String> formSamples = samples(line(ctx, "- 예시값: "));
        List<LlmOutput.Sample> samples = new ArrayList<>();
        for (String v : RuleValidator.variablesIn((text ? nz(line(ctx, "- 강조 제목: ")) : "") + polished)) {
            samples.add(new LlmOutput.Sample(v, formSamples.getOrDefault(v, "예시")));
        }
        return new LlmOutput.DraftOut(orDefault(line(ctx, "- 카테고리: "), "002001"),
                orDefault(line(ctx, "- 이름: "), "주문 완료 안내"), orDefault(line(ctx, "- 코드: "), "ORDER_COMPLETE_01"),
                ex || "MI".equals(msgType) ? LlmOutput.MsgType.EX : LlmOutput.MsgType.BA,
                text ? LlmOutput.Emph.TEXT : LlmOutput.Emph.NONE,
                text ? nz(line(ctx, "- 강조 제목: ")) : "", text ? nz(line(ctx, "- 보조 문구: ")) : "",
                polished, ex ? nz(block(ctx, "- 부가정보:\n")) : "", false, samples);
    }

    /** "머리말 값" 한 줄. 없으면 null. */
    private static String line(String ctx, String head) {
        int s = ctx.indexOf(head);
        if (s < 0) {
            return null;
        }
        s += head.length();
        int e = ctx.indexOf('\n', s);
        return ctx.substring(s, e < 0 ? ctx.length() : e);
    }

    /** "머리말\n여러 줄 값". 다음 항목 머리말 또는 요청 앞에서 끝난다. 없으면 null. */
    private static String block(String ctx, String head) {
        int s = ctx.indexOf(head);
        if (s < 0) {
            return null;
        }
        s += head.length();
        int e = ctx.length();
        for (String end : BLOCK_ENDS) {
            int i = ctx.indexOf(end, s);
            if (i >= 0 && i < e) {
                e = i;
            }
        }
        return e <= s ? "" : ctx.substring(s, e).stripTrailing();
    }

    /** Map.toString 형식("{a=1, b=2}")의 예시값. 값에 ", "가 있으면 정확하지 않지만 가짜 응답에는 충분하다. */
    private static Map<String, String> samples(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (text == null || text.length() < 2) {
            return map;
        }
        for (String pair : text.substring(1, text.length() - 1).split(", ")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(pair.substring(0, i), pair.substring(i + 1));
            }
        }
        return map;
    }

    private static String orDefault(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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
