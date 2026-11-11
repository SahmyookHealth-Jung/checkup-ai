package com.checkupai.report.extraction;

import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * 결과지에 적힌 숫자 값의 표기를 읽는 규칙. 추출 검증과 판정 코드가 같은 규칙을 쓴다 (ADR 0004 보완, 2026-11-11).
 *
 * <p>결과지는 숫자에 높음·낮음 표시("64 L", "9.3▼"), 천 단위 쉼표("1,120"), 부등호(">90")를 붙인다.
 * 두 단계가 규칙을 따로 가지면 한쪽만 고쳐진다. 2026-11-11 표기 변형 평가에서 판정 코드는 "9.3▼"를 읽을 수 있었지만,
 * 추출 검증이 앞에서 "숫자가 아님"으로 버려 판정까지 오지 못했다.
 */
public final class NumericValue {

    /** 숫자 항목 값의 모양 */
    public enum Shape {
        /** 표시·쉼표를 떼면 숫자 하나: "118", "64 L", "9.3▼", "1,120" */
        NUMBER,
        /** 부등호와 숫자 하나: ">90", "<0.1", "400 이상". 실제 수치가 아니라 판정하지 않는다 */
        INEQUALITY,
        /** 그 밖: "정상", "150/95", "13.0~16.5", "650kg". 결과로 읽을 수 없다 */
        OTHER
    }

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
    /**
     * 값 앞뒤의 높음·낮음 표시: "140 H", "H 140", "140▲", "9.3 (L)", "140*", "140 high".
     * 한 글자 표시는 대문자 H/L만 받는다. 소문자는 단위("8h", "1.2 l")일 수 있다 (2026-11-11 Fable 리뷰).
     */
    private static final Pattern FLAG = Pattern.compile(
            "^[\\s(\\[]*(?:(?i:high|low)|[HL]|[▲▼↑↓*])[\\s)\\]]*(?=[-+]?\\d)"
                    + "|(?<=\\d)[\\s(\\[]*(?:(?i:high|low)|[HL]|[▲▼↑↓*])[\\s)\\]]*$");
    /** 천 단위 쉼표: "1,120" */
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");
    /** 실제 수치가 아닌 값: ">90", "<10", "400 이상" */
    private static final Pattern INEQUALITY = Pattern.compile("[<>≤≥≦≧]|이상|이하|미만|초과");
    /** 부등호 값의 모양: 부등호 기호 + 숫자, 또는 숫자 + "이상·이하·미만·초과" */
    private static final Pattern INEQUALITY_VALUE = Pattern.compile(
            "[<>≤≥≦≧]=?\\s*\\d+(\\.\\d+)?|\\d+(\\.\\d+)?\\s*(이상|이하|미만|초과)");

    private NumericValue() {
    }

    /** 높음·낮음 표시와 천 단위 쉼표를 뗀다 ("140 H" → "140", "1,120" → "1120") */
    public static String clean(String value) {
        return THOUSANDS.matcher(FLAG.matcher(value.strip()).replaceAll("")).replaceAll("").strip();
    }

    /** 부등호나 "이상·미만" 같은 말이 들어 있다 (">90") */
    public static boolean hasInequality(String value) {
        return value != null && INEQUALITY.matcher(value).find();
    }

    /** 표시·쉼표를 뗀 뒤 숫자 하나로 읽히면 그 값. 부등호 값이거나 숫자로 읽지 못하면 빈 값 */
    public static OptionalDouble parse(String value) {
        if (value == null || hasInequality(value)) {
            return OptionalDouble.empty();
        }
        String cleaned = clean(value);
        return NUMBER.matcher(cleaned).matches() ? OptionalDouble.of(Double.parseDouble(cleaned)) : OptionalDouble.empty();
    }

    public static Shape shape(String value) {
        if (value == null || value.isBlank()) {
            return Shape.OTHER;
        }
        if (parse(value).isPresent()) {
            return Shape.NUMBER;
        }
        return INEQUALITY_VALUE.matcher(clean(value)).matches() ? Shape.INEQUALITY : Shape.OTHER;
    }
}
