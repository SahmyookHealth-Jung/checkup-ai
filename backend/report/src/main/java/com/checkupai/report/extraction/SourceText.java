package com.checkupai.report.extraction;

import java.util.ArrayList;
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
     * 숫자는 다른 숫자의 일부이면 안 된다 ("12"는 "112" 안에, "18"은 "18.3" 안에, "5"는 "5.2" 안에 있는 것으로 보지 않는다).
     * 2026-11-10 Fable 리뷰: 뒤쪽이 소수점이어도 통과해 "18.3"의 앞부분 "18"을 인정했다. 문장 끝 마침표("18.")는 인정한다.
     * 글자 값은 공백으로 나눈 조각이 모두 들어 있으면 있다고 본다 ("비활동성 폐결핵" 사이 공백이 달라도 된다).
     */
    static boolean containsValue(String text, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.strip();
        if (isNumber(trimmed)) {
            return Pattern.compile("(?<![\\d.])" + Pattern.quote(trimmed) + "(?!\\d|\\.\\d)").matcher(text).find();
        }
        String squashedText = squash(text);
        return Arrays.stream(WHITESPACE.split(trimmed)).allMatch(token -> squashedText.contains(token));
    }

    /**
     * 값이 들어 있는 원문 범위. 값이 한 줄 안에 있으면 그 줄이고, 두 줄에 걸쳐 있으면 두 줄을 이은 것이다.
     * PDF 표 칸에서 긴 값은 줄바꿈된다. 2026-11-09 전체 평가에서 "기타 질환 의심(소견서 참조)"가
     * "... 의심(소" / "견서 참조) - - 질환의심"으로 나뉘어, 같은 칸의 판정을 지우고 둘째 줄을 누락 조각으로 재요청했다.
     */
    static List<String> valueWindows(List<String> lines, String value) {
        List<String> windows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String window = valueWindowAt(lines, i, value);
            if (window != null) {
                windows.add(window);
            }
        }
        return windows;
    }

    /** i번째 줄에서 시작하는 값 범위: 그 줄, 또는 다음 줄과 이은 것. 값이 없으면 null */
    static String valueWindowAt(List<String> lines, int i, String value) {
        if (containsValue(lines.get(i), value)) {
            return lines.get(i);
        }
        if (i + 1 < lines.size() && spansLines(lines.get(i), lines.get(i + 1), value)) {
            return lines.get(i) + " " + lines.get(i + 1);
        }
        return null;
    }

    /** 값이 두 줄 중 어느 한 줄에도 온전히 없고, 두 줄을 이으면 있다 (줄바꿈으로 나뉜 값). 숫자는 나뉘지 않는다. */
    static boolean spansLines(String first, String second, String value) {
        return !containsValue(first, value) && !containsValue(second, value)
                && containsValue(first + " " + second, value);
    }

    static boolean containsIgnoringSpaces(String text, String fragment) {
        return squash(text).contains(squash(fragment));
    }

    private static final Pattern HEADER_WORD = Pattern.compile("검사명|검사항목|항목|결과|금회|전회|단위|참고치|정상범위|판정|구분");
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("[,，]");

    /** 안내문·문장: "※"로 시작하거나 "~니다", "~요", 마침표로 끝나는 줄. 표 머리가 아니다. */
    private static final Pattern SENTENCE = Pattern.compile("^※.*|.*(니다|요|\\.)\\s*$");

    /**
     * 표 머리 줄: 머리말 단어가 2개 이상이고 숫자가 없으며 문장이 아닌 줄. 재요청할 때 열 구성을 알려 주는 데 쓴다.
     * (2026-11-09: "※ 전회 결과는 직전 검진 기록입니다."가 '전회', '결과' 때문에 머리 줄로 잡혀 문장 제외 조건을 추가)
     */
    static List<String> headerLines(String source) {
        return lines(source).stream().filter(SourceText::isHeaderLine).toList();
    }

    static boolean isHeaderLine(String line) {
        return !line.matches(".*\\d.*")
                && !SENTENCE.matcher(line).matches()
                && HEADER_WORD.matcher(line).results().count() >= 2;
    }

    /**
     * 한 줄을 쉼표로 나눈 조각. 서술형 결과지는 "시력 좌 0.7, 시력 우 0.8, 청력 좌 정상"처럼 한 줄에 여러 항목을 쓴다.
     * 표 형식 줄에는 쉼표가 없으므로 줄 전체가 한 조각이다.
     */
    static List<String> segments(String line) {
        return Arrays.stream(SEGMENT_SEPARATOR.split(line))
                .map(String::strip)
                .filter(segment -> !segment.isEmpty())
                .toList();
    }

    static boolean isNumber(String value) {
        return value != null && value.strip().matches("[-+]?\\d+(\\.\\d+)?");
    }
}
