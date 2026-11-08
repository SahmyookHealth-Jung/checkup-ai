package com.checkupai.report.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.checkupai.report.extraction.ExtractionValidator.Issue;
import com.checkupai.report.extraction.ExtractionValidator.IssueType;
import com.checkupai.report.extraction.ExtractionValidator.ValidationResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExtractionValidatorTest {

    /** PDF에서 뽑은 텍스트처럼 공백이 들쭉날쭉한 요약표형 결과지 */
    private static final String SOURCE = """
                        가상의원         종합건강검진             결과     요약
                        수검번호:     SYN-2026-6245       이름:    최*준   (F/42)  검사일:     2026.10.26
                         검사명                      금회                       전회                       단위                       참고치
                         SBP                      103                      102                      mmHg                     <120
                         Hb                       10.4                     10.3                     g/dL                     12.0-15.5
                         FBS                      183                      185                      mg/dL                    <100
                         Chest PA                 비활동성     폐결핵             정상
                         HbA1c                    5.1                      5.3                      %                        <5.7
                        ※ 전회   결과는    직전    검진   기록입니다.
            """;

    private final ExtractionValidator validator = new ExtractionValidator();

    @Test
    @DisplayName("같은 코드·같은 값의 반복은 하나만 남긴다")
    void removesRepeatedItems() {
        ValidationResult result = validate(
                item(ItemCode.FASTING_GLUCOSE, "FBS", "183"),
                item(ItemCode.FASTING_GLUCOSE, "FBS", "183"));

        assertThat(result.report().items()).hasSize(1);
        assertThat(types(result)).containsExactly(IssueType.DUPLICATE);
    }

    @Test
    @DisplayName("원문에 없는 값은 버린다. 숫자는 다른 숫자 속 일부(183 속 18)를 인정하지 않는다")
    void dropsValuesNotInSource() {
        ValidationResult result = validate(
                item(ItemCode.AST, "AST", "33"),
                item(ItemCode.ALT, "ALT", "18"));

        assertThat(result.report().items()).isEmpty();
        assertThat(types(result)).containsOnly(IssueType.UNGROUNDED_VALUE);
    }

    @Test
    @DisplayName("숫자가 아니거나 생리적으로 불가능한 값은 버린다")
    void dropsImplausibleNumbers() {
        ValidationResult result = validate(
                item(ItemCode.HEMOGLOBIN, "FBS", "183"),       // 혈색소 183은 불가능: 다른 행을 읽은 것
                item(ItemCode.SYSTOLIC_BP, "SBP", "<120"));    // 참고치를 값으로 잘못 읽음: 숫자가 아님

        assertThat(result.report().items()).isEmpty();
        assertThat(types(result)).containsOnly(IssueType.IMPLAUSIBLE);
    }

    @Test
    @DisplayName("같은 코드에 다른 값이 오면 처음 값만 남긴다")
    void keepsFirstValueOnConflict() {
        ValidationResult result = validate(
                item(ItemCode.FASTING_GLUCOSE, "FBS", "183"),
                item(ItemCode.FASTING_GLUCOSE, "FBS", "185"));

        assertThat(result.report().items()).extracting(ReportItem::value).containsExactly("183");
        assertThat(types(result)).containsExactly(IssueType.CONFLICT);
    }

    @Test
    @DisplayName("값이 있는 줄에 없는 판정·단위는 null로 바꾸고, 있는 것은 남긴다")
    void clearsFieldsNotOnValueLine() {
        ReportItem extracted = new ReportItem(ItemCode.FASTING_GLUCOSE, "FBS", "183", "mg/dL", "<100", "정상A");

        ValidationResult result = validate(extracted);

        ReportItem kept = result.report().items().getFirst();
        assertThat(kept.unit()).isEqualTo("mg/dL");
        assertThat(kept.referenceRaw()).isEqualTo("<100");
        assertThat(kept.centerJudgment()).isNull();
        assertThat(result.issues()).extracting(Issue::type, Issue::detail)
                .containsExactly(tuple(IssueType.UNGROUNDED_FIELD, "centerJudgment=정상A"));
    }

    @Test
    @DisplayName("PDF의 넓은 공백이 끼어 있어도 글자 값을 원문에서 찾는다")
    void matchesTextValuesAcrossWideSpaces() {
        ValidationResult result = validate(item(ItemCode.CHEST_XRAY, "Chest PA", "비활동성 폐결핵"));

        assertThat(result.report().items()).hasSize(1);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("항목명이 원문에 없으면 문제로 기록하되 항목은 남긴다")
    void recordsRawNameNotInSource() {
        ValidationResult result = validate(item(ItemCode.FASTING_GLUCOSE, "공복혈당(Glucose)", "183"));

        assertThat(result.report().items()).hasSize(1);
        assertThat(types(result)).containsExactly(IssueType.RAW_NAME_NOT_IN_SOURCE);
    }

    @Test
    @DisplayName("값이 하나도 추출되지 않은 결과 줄을 누락 의심으로 모은다. 수검자 정보·표 머리·안내문은 뺀다")
    void collectsUncoveredResultLines() {
        ValidationResult result = validate(
                item(ItemCode.SYSTOLIC_BP, "SBP", "103"),
                item(ItemCode.HEMOGLOBIN, "Hb", "10.4"),
                item(ItemCode.FASTING_GLUCOSE, "FBS", "183"));

        assertThat(result.uncoveredLines()).hasSize(2);
        assertThat(result.uncoveredLines().get(0)).startsWith("Chest PA");
        assertThat(result.uncoveredLines().get(1)).startsWith("HbA1c");
    }

    @Test
    @DisplayName("재요청 항목은 기존 결과와 중복을 걸러 붙이고, 기존 항목은 다시 검사하지 않는다")
    void mergesAdditionalItems() {
        ValidationResult first = validate(item(ItemCode.FASTING_GLUCOSE, "공복혈당", "183"));

        ValidationResult merged = validator.validateAdditional(first.report(), List.of(
                item(ItemCode.FASTING_GLUCOSE, "FBS", "183"),
                item(ItemCode.OTHER, "HbA1c", "5.1")), SOURCE);

        assertThat(merged.report().items()).extracting(ReportItem::code, ReportItem::value)
                .containsExactly(tuple(ItemCode.FASTING_GLUCOSE, "183"), tuple(ItemCode.OTHER, "5.1"));
        assertThat(types(merged)).containsExactly(IssueType.DUPLICATE);
    }

    private ValidationResult validate(ReportItem... items) {
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.COMPREHENSIVE,
                CheckupReport.Sex.FEMALE, 42, List.of(items));
        return validator.validate(report, SOURCE);
    }

    private static ReportItem item(ItemCode code, String rawName, String value) {
        return new ReportItem(code, rawName, value, null, null, null);
    }

    private static List<IssueType> types(ValidationResult result) {
        return result.issues().stream().map(Issue::type).toList();
    }
}
