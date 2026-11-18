package com.alimtalk.llm;

import com.alimtalk.template.TemplateDraft;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude 구조화 출력 형식. 스키마에서 Map을 피하려고 예시값은 배열로 받는다.
 * 상태·유형은 enum으로 두어 SDK가 만드는 JSON 스키마에서 허용값만 받도록 한다(화면 계약은 TemplateDraft의 문자열).
 */
public record LlmOutput(
        @JsonPropertyDescription("OK: 템플릿 작성(새로 만들기 또는 다듬기), ASK: 작성에 필요한 정보를 묻는 질문, AD_CONTENT: 광고성 요청이라 작성 불가")
        Status status,
        @JsonPropertyDescription("NEW: 새 메시지를 만들어 달라는 요청, POLISH: 현재 폼 내용을 고쳐 달라는 요청. ASK·AD_CONTENT일 때도 요청의 성격대로 채운다")
        Mode mode,
        @JsonPropertyDescription("사용자에게 보여줄 한국어 한 문장. ASK면 질문, OK면 작성·변경 내용 한 줄 요약, AD_CONTENT면 거절 이유")
        String message,
        @JsonPropertyDescription("버튼으로 보여줄 선택지(최대 6개, 각 20자 이내). ASK면 질문의 답 후보, OK면 후속 제안(예: 고객센터 안내 추가). 없으면 빈 배열")
        List<String> choices,
        @JsonPropertyDescription("템플릿 초안. status가 OK가 아니면 모든 문자열을 빈 문자열로, 배열은 빈 배열로 채운다")
        DraftOut draft) {

    public record DraftOut(
            @JsonPropertyDescription("카테고리 코드") String category,
            @JsonPropertyDescription("템플릿 이름") String name,
            @JsonPropertyDescription("템플릿 코드 (영문·숫자·_)") String code,
            @JsonPropertyDescription("메시지 유형: BA(기본형), EX(부가정보형), AD(채널추가형), MI(복합형=부가정보+채널추가). AD·MI는 새로 만들 때 사용자가 채널 추가를 요청했을 때만, 다듬기에서는 현재 폼의 유형을 유지") MsgType msgType,
            @JsonPropertyDescription("강조 유형: NONE(선택 안 함), TEXT(강조 표기형), IMAGE(이미지형), ITEM_LIST(아이템리스트형). IMAGE·ITEM_LIST는 새로 만들 때 사용자가 요청했을 때만, 다듬기에서는 현재 폼의 유형을 유지") Emph emph,
            @JsonPropertyDescription("강조 제목. emph가 TEXT일 때만, 아니면 빈 문자열") String title,
            @JsonPropertyDescription("강조 보조 문구. 변수 금지. emph가 TEXT일 때만, 아니면 빈 문자열") String sub,
            @JsonPropertyDescription("본문. 변수는 #{변수명}") String content,
            @JsonPropertyDescription("부가정보. 변수 금지. msgType이 EX·MI일 때만, 아니면 빈 문자열") String extra,
            @JsonPropertyDescription("보안 템플릿 여부 (인증번호 등)") boolean security,
            @JsonPropertyDescription("본문·강조 제목·아이템리스트에 쓴 모든 변수의 예시값") List<Sample> samples,
            @JsonPropertyDescription("아이템리스트. emph가 ITEM_LIST일 때만 채우고, 아니면 모든 문자열을 빈 문자열로, list는 빈 배열로") ItemOut item) {

        /** 아이템리스트 없이 만드는 형태(가짜 LLM·테스트용). */
        public DraftOut(String category, String name, String code, MsgType msgType, Emph emph, String title, String sub,
                        String content, String extra, boolean security, List<Sample> samples) {
            this(category, name, code, msgType, emph, title, sub, content, extra, security, samples, null);
        }

        public TemplateDraft toDraft() {
            Map<String, String> map = new LinkedHashMap<>();
            if (samples != null) {
                for (Sample s : samples) {
                    if (s != null && s.name() != null) {
                        map.put(s.name(), s.value());
                    }
                }
            }
            return new TemplateDraft(category, name, code, msgType == null ? null : msgType.name(),
                    emph == null ? null : emph.name(), title, sub, content, extra, security, map,
                    item == null ? null : item.toItem());
        }
    }

    public record ItemOut(
            @JsonPropertyDescription("헤더. 변수 가능") String header,
            @JsonPropertyDescription("아이템 하이라이트 제목. 변수 가능, 없으면 빈 문자열") String hlTitle,
            @JsonPropertyDescription("아이템 하이라이트 설명. 변수 가능, 없으면 빈 문자열") String hlDesc,
            @JsonPropertyDescription("항목 2~10개") List<RowOut> list,
            @JsonPropertyDescription("요약 이름(예: 총 결제금액). 변수 금지. 요약을 쓰지 않으면 빈 문자열") String sumName,
            @JsonPropertyDescription("요약 내용. 숫자·쉼표·마침표·통화기호·통화단위만(예: #{결제금액}원, 이 변수의 예시값은 숫자만 129,000). 요약을 쓰지 않으면 빈 문자열") String sumDesc) {

        TemplateDraft.Item toItem() {
            List<TemplateDraft.Row> rows = list == null ? List.of()
                    : list.stream().filter(r -> r != null).map(r -> new TemplateDraft.Row(r.name(), r.desc())).toList();
            return new TemplateDraft.Item(header, hlTitle, hlDesc, rows, sumName, sumDesc);
        }
    }

    public record RowOut(
            @JsonPropertyDescription("항목명. 짧게, 변수 금지") String name,
            @JsonPropertyDescription("내용. 변수 가능") String desc) {}

    public record Sample(
            @JsonPropertyDescription("변수 이름 (#{ } 없이)") String name,
            @JsonPropertyDescription("예시값") String value) {}

    public enum Status { OK, ASK, AD_CONTENT }

    public enum Mode { NEW, POLISH }

    public enum MsgType { BA, EX, AD, MI }

    public enum Emph { NONE, TEXT, IMAGE, ITEM_LIST }
}
