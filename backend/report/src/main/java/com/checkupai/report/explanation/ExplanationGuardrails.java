package com.checkupai.report.explanation;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 해설의 고정 문구와 금지 표현 (CLAUDE.md 의료 가드레일 원칙, ADR 0007 5절). 문구는 LLM이 아니라 코드가 붙인다.
 */
public final class ExplanationGuardrails {

    /** 원칙 4: 모든 응답에 붙인다 */
    public static final String DISCLAIMER = "이 내용은 의료 진단이 아니며, 정확한 판단은 의료진과 상담하세요.";

    /** 원칙 3: 국가건강검진 기준상 질환의심 구간인 항목에 붙인다 */
    public static final String CARE_ADVICE =
            "국가건강검진 판정기준의 '질환의심' 구간입니다. 정확한 판단과 필요한 검사는 의료진과 상담하세요.";

    /** 원칙 3: 확인 필요(범위 밖) 항목에 붙인다 (ADR 0007 1절) */
    public static final String REVIEW_ADVICE =
            "결과지에서 읽은 값이 일반적인 범위를 크게 벗어납니다. 결과지 원본을 확인하고 의료진과 상담하세요.";

    /** 원칙 3: 판정 대상인데 표기나 단위를 몰라 판정하지 못한 항목에 붙인다 (ADR 0007 보완, 2026-11-11) */
    public static final String UNJUDGED_ADVICE =
            "이 값은 표기나 단위를 확인하지 못해 판정하지 않았습니다. 결과지 원본을 확인하고 의료진과 상담하세요.";

    /**
     * 원칙 3: 판독 소견을 판정하지 못한 흉부방사선에 붙인다. 고시는 "정상 및 비활동성 폐결핵 이외의 자"를 질환의심으로 정하므로,
     * 모르는 소견은 이상 소견일 수 있다. 판정은 하지 않되 기준을 밝혀 경고 강도를 유지한다 (2026-11-11 Codex 리뷰)
     */
    public static final String CHEST_UNJUDGED_ADVICE =
            "이 판독 소견은 판정하지 못했습니다. 국가건강검진 판정기준에서 정상·비활동성 폐결핵 이외의 소견은 "
                    + "질환의심에 해당할 수 있습니다. 결과지 원본을 확인하고 의료진과 상담하세요.";

    /** 원칙 2: 근거를 찾지 못한 항목 */
    public static final String NO_EVIDENCE = "이 항목을 설명할 근거를 공개 문서에서 찾지 못했습니다.";

    /**
     * 원칙 1·5: 진단 단정과 치료 지시. 4단계에서는 막지 않고 위반 건수만 기록한다 (5단계에서 공격 질문 세트로 잰다).
     * 판정 구분 이름("질환의심")과 근거 문장의 일반 설명은 걸리지 않게 단정·지시 형태만 본다.
     */
    static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("[가-힣]+(병|증|암)(입니다|이에요|예요|으로 진단됩니다)"),
            Pattern.compile("(확실|틀림없)(합니다|습니다)"),
            Pattern.compile("(진단|확진)(됩니다|합니다|드립니다)"),
            Pattern.compile("(약|복용|투약|처방)[^.]{0,10}(하세요|하십시오|드세요|중단하|끊으)"),
            Pattern.compile("\\d+\\s*(mg|밀리그램|정)\\s*(을|를)?\\s*(복용|드)"));

    private ExplanationGuardrails() {
    }

    static int forbiddenHits(String text) {
        return (int) FORBIDDEN.stream().filter(pattern -> pattern.matcher(text).find()).count();
    }
}
