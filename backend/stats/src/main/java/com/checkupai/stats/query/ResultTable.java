package com.checkupai.stats.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 실행 결과와 작은 집단 숨김 (ADR 0013 3절, 보완).
 *
 * <p>숨김은 LLM이 쓴 n이 아니라 서버가 덧붙인 숨은 인원 열({@link SqlGuard#hiddenCounts})로 정한다: 행마다 묶음 인원과
 * 각 집계 인자의 인원 중 가장 작은 값이 상한보다 작으면 그 행을 숨긴다. 숨긴 행은 숫자를 모두 가리고(n 포함),
 * 글자는 안전한 이름표(시도 이름, 연령대 이름, SQL에 직접 쓴 문자열)만 남긴다 — 값을 글자로 바꿔 묶음 이름으로
 * 내보내는 우회를 막는다. 숨은 열은 결과에서 뺀다.
 *
 * @param suppressedRows 숨긴 행 수
 * @param truncated 행 상한에 걸려 잘렸는지
 */
public record ResultTable(List<String> columns, List<List<Object>> rows, int suppressedRows, boolean truncated) {

    public static final String COUNT_COLUMN = "n";

    /** n 컬럼 위치. 없으면 -1 */
    public static int countIndex(List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (COUNT_COLUMN.equalsIgnoreCase(columns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param hiddenColumns 맨 뒤의 숨은 인원 열 수 (SqlGuard가 덧붙인 것)
     * @param safeLabels 숨긴 행에서도 보여 줄 수 있는 글자 값
     */
    public static ResultTable suppress(List<String> columns, List<List<Object>> rows, int hiddenColumns, int minGroupSize,
                                       boolean truncated, Set<String> safeLabels) {
        if (hiddenColumns < 1 || hiddenColumns > columns.size()) {
            throw new IllegalArgumentException("숨은 인원 열이 없다");
        }
        int visible = columns.size() - hiddenColumns;
        List<List<Object>> out = new ArrayList<>();
        int suppressed = 0;
        for (List<Object> row : rows) {
            long base = Long.MAX_VALUE;
            for (int i = visible; i < row.size(); i++) {
                base = Math.min(base, row.get(i) instanceof Number number ? number.longValue() : 0L);
            }
            List<Object> shown = new ArrayList<>(row.subList(0, visible));
            if (base >= minGroupSize) {
                out.add(shown);
                continue;
            }
            suppressed++;
            List<Object> masked = new ArrayList<>(visible);
            for (Object value : shown) {
                masked.add(value instanceof String text && safeLabels.contains(text) ? text : null);
            }
            out.add(masked);
        }
        return new ResultTable(List.copyOf(columns.subList(0, visible)), out, suppressed, truncated);
    }
}
