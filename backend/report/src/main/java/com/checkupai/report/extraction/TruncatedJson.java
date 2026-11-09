package com.checkupai.report.extraction;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 출력 토큰 상한에서 잘린 JSON을 마지막으로 완성된 항목까지 되살린다.
 *
 * <p>2026-11-09 전체 평가 sum-008: 1차 추출이 반복 루프에 빠져 출력 상한(2,500토큰)에 걸렸고,
 * JSON이 37번째 항목 중간에서 끊겨 요청 전체가 실패했다. 끊기기 전의 항목은 온전했다.
 *
 * <p>{@code "items"} 배열이 루트 객체의 마지막 속성일 때만 쓸 수 있다({@link CheckupReport}, {@link ItemList}).
 * 되살린 항목도 원문 대조 검증을 똑같이 거친다.
 */
final class TruncatedJson {

    private static final Pattern ITEMS_ARRAY = Pattern.compile("\"items\"\\s*:\\s*\\[");

    private TruncatedJson() {
    }

    /**
     * @param json 출력 원문
     * @return 완성된 항목까지 자르고 배열과 객체를 닫은 JSON. items 배열이 이미 닫혔거나 완성된 항목이 없으면 null
     */
    static Repair closeAfterLastCompleteItem(String json) {
        if (json == null) {
            return null;
        }
        Matcher matcher = ITEMS_ARRAY.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        int depth = 1;  // items 배열 안
        int lastItemEnd = -1;
        int completeItems = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = matcher.end(); i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth == 0) {
                        return null;  // 배열이 닫혔다: 잘린 것이 아니다
                    }
                    if (depth == 1 && c == '}') {
                        lastItemEnd = i;
                        completeItems++;
                    }
                }
                default -> {
                }
            }
        }
        if (lastItemEnd < 0) {
            return null;
        }
        return new Repair(json.substring(0, lastItemEnd + 1) + "]}", completeItems);
    }

    /**
     * @param json 닫은 JSON
     * @param items 살린 항목 수
     */
    record Repair(String json, int items) {
    }
}
