package com.checkupai.core.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmBudgetTest {

    @Test
    @DisplayName("오늘 쓴 비용이 상한 이상이면 막고, 아래면 통과한다. 상한이 0이면 끈다")
    void blocksAtLimit() {
        var limit = new LlmBudget.Properties(new BigDecimal("0.20"));
        assertThatCode(() -> new LlmBudget(() -> new BigDecimal("0.1999"), limit).check()).doesNotThrowAnyException();
        assertThatThrownBy(() -> new LlmBudget(() -> new BigDecimal("0.20"), limit).check())
                .isInstanceOf(LlmBudget.ExceededException.class).hasMessageContaining("0.20");
        assertThatCode(() -> new LlmBudget(() -> new BigDecimal("5"), new LlmBudget.Properties(BigDecimal.ZERO)).check())
                .doesNotThrowAnyException();
    }
}
