package com.checkupai.report.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkupai.report.explanation.ExplanationDraft.SentenceDraft;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExplanationValidatorTest {

    private final ExplanationValidator validator = new ExplanationValidator();
    private static final Map<String, String> SOURCES = Map.of(
            "S1", "건강검진 실시기준 검사항목별 판정기준 — 공복 혈당 / 정상A: 100 미만 / 정상B(경계): 100-125 / 질환의심: 126 이상",
            "S2", "[당뇨병 | 진단 및 검사] 8시간 이상 공복 후 혈장 포도당 126 mg/dL 이상이면 당뇨병으로 진단합니다.");
    private static final String FACTS = "183 mg/dL\n공복 혈당 · 정상A 100 미만 / 정상B(경계) 100-125 / 질환의심 126 이상";

    @Test
    @DisplayName("허락된 근거를 인용하고 숫자가 결과값·기준·인용 근거에 있는 문장만 남긴다")
    void keepsCitedAndGroundedSentences() {
        var outcome = validator.check(List.of(
                new SentenceDraft("공복혈당 183 mg/dL은 126 이상 구간에 해당합니다.", List.of("S1")),
                new SentenceDraft("근거 문서는 8시간 이상 공복 후 126 mg/dL 이상을 설명합니다.", List.of("[S2]"))),
                SOURCES, FACTS);

        assertThat(outcome.kept()).extracting(ExplanationValidator.CheckedSentence::sources)
                .containsExactly(List.of("S1"), List.of("S2"));
        assertThat(outcome.generated()).isEqualTo(2);
    }

    @Test
    @DisplayName("허락되지 않은 근거 번호는 지우고, 유효한 인용이 없는 문장은 버린다")
    void dropsUncitedSentences() {
        var outcome = validator.check(List.of(
                new SentenceDraft("공복혈당 183 mg/dL입니다.", List.of("S9")),
                new SentenceDraft("기준은 126 이상입니다.", List.of("S1", "S7"))),
                SOURCES, FACTS);

        assertThat(outcome.kept()).singleElement().satisfies(sentence -> assertThat(sentence.sources()).containsExactly("S1"));
        assertThat(outcome.invalidCitations()).isEqualTo(2);
        assertThat(outcome.uncited()).isEqualTo(1);
    }

    @Test
    @DisplayName("결과값·기준·인용 근거에 없는 숫자가 든 문장은 버린다 (지어낸 수치)")
    void dropsSentencesWithUngroundedNumbers() {
        var outcome = validator.check(List.of(
                new SentenceDraft("당화혈색소 6.5% 이상이면 당뇨병입니다.", List.of("S1")),
                new SentenceDraft("공복혈당 183.0 mg/dL은 126 이상입니다.", List.of("S1"))),
                SOURCES, FACTS);

        assertThat(outcome.kept()).hasSize(1);  // 183.0은 183과 같은 수로 본다
        assertThat(outcome.ungroundedNumbers()).isEqualTo(1);
        assertThat(outcome.forbiddenHits()).isEqualTo(1);  // "당뇨병입니다"는 진단 단정 (기록만)
    }
}
