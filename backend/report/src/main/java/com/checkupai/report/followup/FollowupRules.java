package com.checkupai.report.followup;

import java.util.regex.Pattern;

/**
 * 질문의 약·진단 패턴 규칙 (ADR 0011 3절). 지금은 기록만 하고 막지 않는다.
 * 개발 몫에서 LLM이 놓친 거절을 규칙이 하나라도 잡고, 답 가능 질문에 오탐이 0일 때만 "규칙 또는 LLM"으로 켠다.
 * "약"을 부분 문자열로 찾으면 "약간", "요약", "약양성"에서도 걸리므로 약을 다루는 말의 형태로만 찾는다.
 */
final class FollowupRules {

    /** 약·영양제·치료를 정해 달라는 질문 */
    static final Pattern TREATMENT = Pattern.compile(
            "(?<![가-힣])약(을|이|은|는|도)?\\s*(먹|처방|끊|중단|복용|드|바꾸)|[가-힣]+약(을|이|은|는)?\\s*(먹|끊|처방)"
                    + "|영양제|철분제|건강식품|한약|처방|복용|용량|몇\\s*알|\\d+\\s*mg|스타틴|수술(을|이)?\\s*받");

    /** 나에게 병이 있는지 단정해 달라는 질문 */
    static final Pattern DIAGNOSIS = Pattern.compile(
            "(무슨|어떤)\\s*병(이|인)|병인가|병인지|병이에요|몇\\s*기|"
                    + "(당뇨|고혈압|간경화|간경변|간암|콩팥병|신부전|빈혈|이상지질혈증)[가-힣]{0,3}\\s*(인가|인 건가|인건가|맞|확실|이에요|예요|일까)");

    record Flags(boolean treatment, boolean diagnosis) {
    }

    private FollowupRules() {
    }

    static Flags check(String question) {
        String text = question == null ? "" : question;
        return new Flags(TREATMENT.matcher(text).find(), DIAGNOSIS.matcher(text).find());
    }
}
