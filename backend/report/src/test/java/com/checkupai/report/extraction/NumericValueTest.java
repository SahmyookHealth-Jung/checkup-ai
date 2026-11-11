package com.checkupai.report.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkupai.report.extraction.NumericValue.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NumericValueTest {

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("값의 모양: 표시·쉼표를 떼면 숫자 하나 / 부등호 + 숫자 하나 / 그 밖")
    @CsvSource(delimiter = '|', value = {
            "118 | NUMBER", "64 L | NUMBER", "H 140 | NUMBER", "9.3▼ | NUMBER", "9.3 (L) | NUMBER", "1,120 | NUMBER",
            "140 high | NUMBER", "140* | NUMBER",
            ">90 | INEQUALITY", "> 90 | INEQUALITY", "<0.1 | INEQUALITY", "≥60 | INEQUALITY", "400 이상 | INEQUALITY",
            "정상 | OTHER", "150/95 | OTHER", "13.0~16.5 | OTHER", "650kg | OTHER", "183g/dL | OTHER",
            "140 HDL | OTHER", "60 이상 120 미만 | OTHER", "<120 / 80 | OTHER",
            // 소문자 한 글자는 표시가 아니라 단위일 수 있다 (2026-11-11 Fable 리뷰: "8h"가 8로 읽혔다)
            "8h | OTHER", "1.2 l | OTHER"})
    void classifiesShape(String value, Shape expected) {
        assertThat(NumericValue.shape(value)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("숫자로 읽을 때는 표시와 천 단위 쉼표를 뗀다")
    @CsvSource(delimiter = '|', value = {"64 L | 64", "9.3▼ | 9.3", "1,120 | 1120", "H 140 | 140", "140 low | 140"})
    void parsesDecoratedNumber(String value, double expected) {
        assertThat(NumericValue.parse(value)).hasValue(expected);
    }
}
