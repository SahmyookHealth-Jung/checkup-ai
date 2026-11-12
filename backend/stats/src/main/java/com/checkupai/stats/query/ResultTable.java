package com.checkupai.stats.query;

import java.util.ArrayList;
import java.util.List;

/**
 * 실행 결과와 작은 집단 숨김 (ADR 0013 3절).
 * 행마다 n(그 값의 바탕이 된 사람 수)이 있어야 하고, n이 상한보다 작으면 그 행의 숫자 값과 n을 숨긴다(null). 묶음 이름(글자)은 남긴다.
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

    public static ResultTable suppress(List<String> columns, List<List<Object>> rows, int minGroupSize, boolean truncated) {
        int n = countIndex(columns);
        if (n < 0) {
            throw new IllegalArgumentException("n 컬럼이 없다");
        }
        List<List<Object>> out = new ArrayList<>();
        int suppressed = 0;
        for (List<Object> row : rows) {
            Object count = row.get(n);
            boolean small = count == null || !(count instanceof Number number) || number.longValue() < minGroupSize;
            if (!small) {
                out.add(row);
                continue;
            }
            suppressed++;
            List<Object> masked = new ArrayList<>(row.size());
            for (Object value : row) {
                masked.add(value instanceof Number ? null : value);
            }
            out.add(masked);
        }
        return new ResultTable(columns, out, suppressed, truncated);
    }
}
