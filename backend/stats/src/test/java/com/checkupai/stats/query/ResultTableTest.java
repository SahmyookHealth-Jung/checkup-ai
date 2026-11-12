package com.checkupai.stats.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResultTableTest {

    @Test
    @DisplayName("n이 상한보다 작은 행은 숫자 값과 n을 숨기고, 묶음 이름은 남긴다")
    void suppressesSmallGroups() {
        List<List<Object>> rows = List.of(
                List.of("서울특별시", new BigDecimal("101.2"), 175300L),
                List.of("세종특별자치시", new BigDecimal("99.0"), 9L));
        ResultTable table = ResultTable.suppress(List.of("region", "avg_glucose", "N"), rows, 10, false);

        assertThat(table.suppressedRows()).isEqualTo(1);
        assertThat(table.rows().get(0)).containsExactly("서울특별시", new BigDecimal("101.2"), 175300L);
        assertThat(table.rows().get(1)).isEqualTo(Arrays.asList("세종특별자치시", null, null));
    }

    @Test
    @DisplayName("n 컬럼이 없으면 받지 않는다")
    void requiresCountColumn() {
        assertThatThrownBy(() -> ResultTable.suppress(List.of("avg"), List.of(List.of(1)), 10, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
