package com.checkupai.report.extraction;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 결과지 원문 대조 도구. PDF 추출 텍스트는 단어 사이 공백이 들쭉날쭉하므로 공백을 무시하고 비교한다.
 */
final class SourceText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SourceText() {
    }

    static List<String> lines(String source) {
        return Arrays.stream(source.split("\\R"))
                .map(SourceText::collapse)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    static String collapse(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    /** 공백을 모두 지운 형태. "120/80  미만"과 "120/80 미만"을 같게 본다. */
    static String squash(String text) {
        return WHITESPACE.matcher(text).replaceAll("");
    }

    /**
     * 값이 텍스트에 있는가.
     * 숫자는 앞뒤가 다른 숫자와 붙어 있으면 안 된다 ("12"는 "112" 안에 있는 것으로 보지 않는다).
     * 글자 값은 공백으로 나눈 조각이 모두 들어 있으면 있다고 본다 ("비활동성 폐결핵" 사이 공백이 달라도 된다).
     */
    static boolean containsValue(String text, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.strip();
        if (isNumber(trimmed)) {
            return Pattern.compile("(?<![\\d.])" + Pattern.quote(trimmed) + "(?![\\d])").matcher(text).find();
        }
        String squashedText = squash(text);
        return Arrays.stream(WHITESPACE.split(trimmed)).allMatch(token -> squashedText.contains(token));
    }

    static boolean containsIgnoringSpaces(String text, String fragment) {
        return squash(text).contains(squash(fragment));
    }

    static boolean isNumber(String value) {
        return value != null && value.strip().matches("[-+]?\\d+(\\.\\d+)?");
    }
}
