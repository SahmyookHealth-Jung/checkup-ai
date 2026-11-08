package com.checkupai.report.extraction;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 결과지 검사 항목의 표준 코드. 국가 일반건강검진 항목 + {@link #OTHER}.
 *
 * <p>기관마다 다른 항목명(식전혈당, FBS, Glucose 등)을 하나의 코드로 모은다.
 * 이후 RAG 검색 키와 평가 매칭 단위로 쓴다. 기준 수치는 여기에 두지 않는다.
 */
public enum ItemCode {

    HEIGHT("신장", ValueKind.NUMERIC, "cm"),
    WEIGHT("체중", ValueKind.NUMERIC, "kg"),
    WAIST_CIRCUMFERENCE("허리둘레", ValueKind.NUMERIC, "cm"),
    BMI("체질량지수(BMI)", ValueKind.NUMERIC, "kg/m²"),
    SYSTOLIC_BP("수축기 혈압", ValueKind.NUMERIC, "mmHg"),
    DIASTOLIC_BP("이완기 혈압", ValueKind.NUMERIC, "mmHg"),
    VISION_LEFT("시력(좌)", ValueKind.NUMERIC, null),
    VISION_RIGHT("시력(우)", ValueKind.NUMERIC, null),
    HEARING_LEFT("청력(좌)", ValueKind.TEXT, null),
    HEARING_RIGHT("청력(우)", ValueKind.TEXT, null),
    URINE_PROTEIN("요단백", ValueKind.TEXT, null),
    HEMOGLOBIN("혈색소(Hb)", ValueKind.NUMERIC, "g/dL"),
    FASTING_GLUCOSE("공복혈당(Glucose)", ValueKind.NUMERIC, "mg/dL"),
    TOTAL_CHOLESTEROL("총콜레스테롤", ValueKind.NUMERIC, "mg/dL"),
    HDL_CHOLESTEROL("HDL 콜레스테롤", ValueKind.NUMERIC, "mg/dL"),
    TRIGLYCERIDES("중성지방(TG)", ValueKind.NUMERIC, "mg/dL"),
    LDL_CHOLESTEROL("LDL 콜레스테롤", ValueKind.NUMERIC, "mg/dL"),
    SERUM_CREATININE("혈청 크레아티닌", ValueKind.NUMERIC, "mg/dL"),
    EGFR("신사구체여과율(eGFR)", ValueKind.NUMERIC, "mL/min/1.73m²"),
    AST("AST(SGOT)", ValueKind.NUMERIC, "U/L"),
    ALT("ALT(SGPT)", ValueKind.NUMERIC, "U/L"),
    GAMMA_GTP("감마지티피(γ-GTP)", ValueKind.NUMERIC, "U/L"),
    CHEST_XRAY("흉부방사선", ValueKind.TEXT, null),
    OTHER("위 목록에 없는 검사", ValueKind.ANY, null);

    /** 값의 종류. 숫자 변환·범위 검사 대상인지 판단할 때 쓴다. */
    public enum ValueKind { NUMERIC, TEXT, ANY }

    private final String koreanName;
    private final ValueKind kind;
    private final String canonicalUnit;

    ItemCode(String koreanName, ValueKind kind, String canonicalUnit) {
        this.koreanName = koreanName;
        this.kind = kind;
        this.canonicalUnit = canonicalUnit;
    }

    public String koreanName() {
        return koreanName;
    }

    public ValueKind kind() {
        return kind;
    }

    /** 대표 단위. 단위가 없는 항목은 null. */
    public String canonicalUnit() {
        return canonicalUnit;
    }

    /** 프롬프트에 넣을 "코드: 한국어명" 목록. */
    public static String promptTable() {
        return Arrays.stream(values())
                .map(code -> "- " + code.name() + ": " + code.koreanName)
                .collect(Collectors.joining("\n"));
    }
}
