package com.checkupai.report.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CharBigramBm25Test {

    @Test
    @DisplayName("평가 스크립트(Python bm25_rank)와 같은 점수를 낸다. 기준값은 Python으로 계산했다")
    void matchesEvalScript() {
        double[] scores = CharBigramBm25.scores("추가로 어떤 검사를 받아봐야 할까요?", List.of(
                "당뇨병은 혈당검사로 진단합니다. 선별검사를 받아야 합니다.",
                "정기적으로 의료기관을 방문해 혈압을 점검해야 합니다.",
                "철분이 많은 음식을 먹습니다."));

        assertThat(scores[0]).isCloseTo(3.891966, within(1e-6));
        assertThat(scores[1]).isZero();
        assertThat(scores[2]).isZero();
    }

    @Test
    @DisplayName("공백을 지우고 두 글자씩 자른다 (조사가 붙어도 '검사'가 겹친다)")
    void countsBigramsWithoutSpaces() {
        assertThat(CharBigramBm25.counts("검사를 받다")).containsEntry("검사", 1).containsEntry("사를", 1)
                .containsEntry("를받", 1).containsEntry("받다", 1).hasSize(4);
        assertThat(CharBigramBm25.counts("")).isEmpty();
    }
}
