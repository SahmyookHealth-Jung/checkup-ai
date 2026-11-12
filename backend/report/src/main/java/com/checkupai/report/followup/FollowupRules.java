package com.checkupai.report.followup;

import java.util.regex.Pattern;

/**
 * 질문의 약·진단 패턴 규칙 (ADR 0011 3절).
 * 진단 규칙은 "규칙 또는 LLM"으로 켰다 (ADR 0011 결과, 사용자 결정 2026-11-12: 시험 몫에서 LLM이 진단 질문 2건에 답함).
 * 약·치료 규칙은 LLM이 놓친 경우가 없어 기록만 한다.
 * "약"을 부분 문자열로 찾으면 "약간", "요약", "약양성"에서도 걸리므로 약을 다루는 말의 형태로만 찾는다.
 */
final class FollowupRules {

    /** 약·영양제·치료를 정해 달라는 질문 */
    static final Pattern TREATMENT = Pattern.compile(
            "(?<![가-힣])약(을|이|은|는|도)?\\s*(먹|처방|끊|중단|복용|드|바꾸)|[가-힣]+약(을|이|은|는)?\\s*(먹|끊|처방)"
                    + "|영양제|철분제|건강식품|한약|처방|복용|용량|몇\\s*알|\\d+\\s*mg|스타틴|수술(을|이)?\\s*받");

    /** 판정기준표의 목표질환과 그 흔한 이름 (건강검진 실시기준 별표 4의 별첨) */
    private static final String DISEASE =
            "(당뇨|고혈압|이상지질혈증|고지혈증|콩팥병|신장병|신장질환|신부전|간질환|간장질환|간경화|간경변|간암|간염|지방간|빈혈|결핵)";

    /** 나에게 병이 있는지 단정해 달라는 질문. "○○이 생긴 건가요", "○○까지 간 건가요", "○○ 확정이죠"도 잡는다 */
    static final Pattern DIAGNOSIS = Pattern.compile(
            "(무슨|어떤)\\s*병(이|인)|병인가|병인지|병이에요|몇\\s*기"
                    + "|" + DISEASE + "[가-힣]{0,3}\\s*(인가|인 건가|인건가|맞|확실|이에요|예요|일까)"
                    + "|" + DISEASE + "[가-힣\\s]{0,10}?(걸린|생긴|간|된|있는)\\s*(건가|건지|거죠|거지|거예요|거야)"
                    + "|" + DISEASE + "\\s*(확정|확진|환자)");

    record Flags(boolean treatment, boolean diagnosis) {
    }

    private FollowupRules() {
    }

    static Flags check(String question) {
        String text = question == null ? "" : question;
        return new Flags(TREATMENT.matcher(text).find(), DIAGNOSIS.matcher(text).find());
    }
}
