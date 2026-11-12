package com.checkupai.stats.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResultTableTest {

    private static final List<String> COLUMNS = List.of("region", "avg_glucose", "n", "__k0", "__k1");

    @Test
    @DisplayName("숨은 인원 열의 가장 작은 값이 상한보다 작으면 숫자를 모두 가리고, 숨은 열은 결과에서 뺀다")
    void suppressesBySmallestHiddenCount() {
        List<List<Object>> rows = List.of(
                List.of("서울특별시", new BigDecimal("101.2"), 175300L, 175300L, 174000L),
                // LLM이 n을 크게 썼어도 평균의 실제 바탕(__k1)이 5명이면 숨긴다 (Codex 적대적 리뷰의 우회)
                List.of("세종특별자치시", new BigDecimal("157.0"), 1000000L, 1000000L, 5L));
        ResultTable table = ResultTable.suppress(COLUMNS, rows, 2, 10, false, Set.of("서울특별시", "세종특별자치시"));

        assertThat(table.columns()).containsExactly("region", "avg_glucose", "n");
        assertThat(table.suppressedRows()).isEqualTo(1);
        assertThat(table.rows().get(0)).containsExactly("서울특별시", new BigDecimal("101.2"), 175300L);
        assertThat(table.rows().get(1)).isEqualTo(Arrays.asList("세종특별자치시", null, null));
    }

    @Test
    @DisplayName("숨긴 행에서는 안전한 이름표가 아닌 글자(값을 글자로 바꾼 것)도 가린다")
    void masksUnsafeTextInSuppressedRows() {
        List<List<Object>> rows = List.of(List.of("206", new BigDecimal("1"), 1L, 1L, 1L));
        ResultTable table = ResultTable.suppress(COLUMNS, rows, 2, 10, false, Set.of("서울특별시"));
        assertThat(table.rows().get(0)).isEqualTo(Arrays.asList(null, null, null));
    }

    @Test
    @DisplayName("숨은 인원 열이 없으면 받지 않는다")
    void requiresHiddenColumns() {
        assertThatThrownBy(() -> ResultTable.suppress(List.of("avg"), List.of(List.of(1)), 0, 10, false, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
