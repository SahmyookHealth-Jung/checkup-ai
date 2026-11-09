package com.checkupai.report.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceTextTest {

    @Test
    @DisplayName("표 머리 줄만 고른다: 머리말 단어 2개 이상, 숫자 없음")
    void picksHeaderLines() {
        String source = """
                   가상의원   종합건강검진   결과   요약
                   검사명        금회        전회        단위        참고치
                   FBS           183         185         mg/dL       <100
                   성명   생년월일   성별   나이   검진일   검진기관
                """;

        assertThat(SourceText.headerLines(source)).containsExactly("검사명 금회 전회 단위 참고치");
    }

    @Test
    @DisplayName("줄을 쉼표로 나눈다. 쉼표가 없으면 줄 전체가 한 조각이다")
    void splitsSegmentsByComma() {
        assertThat(SourceText.segments("· 키 173.0cm, 몸무게 68.5kg, 체질량지수 22.9kg/m² (정상A)"))
                .containsExactly("· 키 173.0cm", "몸무게 68.5kg", "체질량지수 22.9kg/m² (정상A)");
        assertThat(SourceText.segments("FBS 183 185 mg/dL <100")).containsExactly("FBS 183 185 mg/dL <100");
    }

    @Test
    @DisplayName("숫자 값은 다른 숫자의 일부를 인정하지 않고, 글자 값은 공백 차이를 무시한다")
    void containsValue() {
        assertThat(SourceText.containsValue("FBS 183 185", "183")).isTrue();
        assertThat(SourceText.containsValue("FBS 183 185", "18")).isFalse();
        assertThat(SourceText.containsValue("Chest PA 비활동성     폐결핵", "비활동성 폐결핵")).isTrue();
    }
}
