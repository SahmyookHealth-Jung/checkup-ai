package com.checkupai.report.explanation;

/** 국가건강검진 판정 구분 (건강검진 실시기준 별표 4). 코드가 기준 데이터로 판정한다 (ADR 0007 4절) */
public enum Judgment {
    NORMAL_A("정상A"),
    NORMAL_B("정상B(경계)"),
    SUSPECT("질환의심"),
    /** 기준 표에 없는 항목·구간이거나, 성별을 몰라 기준을 고를 수 없다. 판정하지 않는다 */
    NOT_JUDGED("판정 기준 없음");

    private final String label;

    Judgment(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
