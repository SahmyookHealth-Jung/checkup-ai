package com.checkupai.report.explanation;

import static com.checkupai.report.extraction.CheckupReport.Sex.FEMALE;
import static com.checkupai.report.extraction.CheckupReport.Sex.MALE;
import static com.checkupai.report.extraction.CheckupReport.Sex.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.ReportItem;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** 건강검진 실시기준 별표 4 별첨의 경계값을 그대로 확인한다 */
class ScreeningCriteriaTest {

    private final ScreeningCriteria criteria = new ScreeningCriteria();

    @ParameterizedTest(name = "{0} {1} ({2}) → {3}")
    @DisplayName("숫자 항목: 표의 경계값에서 판정이 바뀐다 ('미만'·'이상'·'이하'·'초과', 성별 기준)")
    @CsvSource({
            "FASTING_GLUCOSE, 99, MALE, NORMAL_A", "FASTING_GLUCOSE, 100, MALE, NORMAL_B",
            "FASTING_GLUCOSE, 125, MALE, NORMAL_B", "FASTING_GLUCOSE, 126, MALE, SUSPECT",
            "SYSTOLIC_BP, 119, MALE, NORMAL_A", "SYSTOLIC_BP, 120, MALE, NORMAL_B", "SYSTOLIC_BP, 140, MALE, SUSPECT",
            "DIASTOLIC_BP, 89, MALE, NORMAL_B", "DIASTOLIC_BP, 90, MALE, SUSPECT",
            "BMI, 18.4, MALE, NORMAL_B", "BMI, 18.5, MALE, NORMAL_A", "BMI, 24.9, MALE, NORMAL_A",
            "BMI, 25.0, MALE, NORMAL_B", "BMI, 30, MALE, SUSPECT",
            "HDL_CHOLESTEROL, 39, MALE, SUSPECT", "HDL_CHOLESTEROL, 40, MALE, NORMAL_B", "HDL_CHOLESTEROL, 60, MALE, NORMAL_A",
            "AST, 40, MALE, NORMAL_A", "AST, 41, MALE, NORMAL_B", "AST, 50, MALE, NORMAL_B", "AST, 51, MALE, SUSPECT",
            "SERUM_CREATININE, 1.5, MALE, NORMAL_A", "SERUM_CREATININE, 1.51, MALE, SUSPECT",
            "EGFR, 59, MALE, SUSPECT", "EGFR, 60, MALE, NORMAL_A",
            "HEMOGLOBIN, 12.9, MALE, NORMAL_B", "HEMOGLOBIN, 12.9, FEMALE, NORMAL_A", "HEMOGLOBIN, 9.9, FEMALE, SUSPECT",
            "WAIST_CIRCUMFERENCE, 89, MALE, NORMAL_A", "WAIST_CIRCUMFERENCE, 85, FEMALE, SUSPECT",
            "GAMMA_GTP, 63, MALE, NORMAL_A", "GAMMA_GTP, 64, MALE, NORMAL_B", "GAMMA_GTP, 78, MALE, SUSPECT",
            "GAMMA_GTP, 46, FEMALE, SUSPECT"
    })
    void judgesNumbersAtBoundaries(ItemCode code, String value, CheckupReport.Sex sex, Judgment expected) {
        assertThat(criteria.judge(code, value, sex).judgment()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @DisplayName("글자 항목: 요단백과 흉부방사선은 표의 표현으로 판정한다")
    @CsvSource({
            "URINE_PROTEIN, 음성, NORMAL_A", "URINE_PROTEIN, 약양성(±), NORMAL_B", "URINE_PROTEIN, 양성(2+), SUSPECT",
            "CHEST_XRAY, 정상, NORMAL_A", "CHEST_XRAY, 비활동성 폐결핵, NORMAL_B",
            "CHEST_XRAY, 기타 질환 의심(소견서 참조), SUSPECT"
    })
    void judgesTextValues(ItemCode code, String value, Judgment expected) {
        assertThat(criteria.judge(code, value, MALE).judgment()).isEqualTo(expected);
    }

    @Test
    @DisplayName("표에 없는 구간·항목, 성별을 모르는 성별 기준, 숫자가 아닌 값은 판정하지 않는다")
    void doesNotJudgeOutsideTheTable() {
        assertThat(criteria.judge(ItemCode.HEMOGLOBIN, "16.6", MALE).judgment()).isEqualTo(Judgment.NOT_JUDGED);
        assertThat(criteria.judge(ItemCode.GAMMA_GTP, "10", MALE).judgment()).isEqualTo(Judgment.NOT_JUDGED);
        assertThat(criteria.judge(ItemCode.HEMOGLOBIN, "11.0", UNKNOWN).judgment()).isEqualTo(Judgment.NOT_JUDGED);  // 남 질환의심, 여 정상B
        assertThat(criteria.judge(ItemCode.FASTING_GLUCOSE, "<100", MALE).judgment()).isEqualTo(Judgment.NOT_JUDGED);
        assertThat(criteria.judge(ItemCode.VISION_LEFT, "1.0", MALE).judgment()).isEqualTo(Judgment.NOT_JUDGED);
        assertThat(criteria.judge(ItemCode.OTHER, "5.4", MALE).judgment()).isEqualTo(Judgment.NOT_JUDGED);
    }

    @Test
    @DisplayName("성별을 몰라도 두 성별 기준의 판정이 같으면 그 판정을 쓴다 (추출이 성별을 놓쳐도 위험 수치를 놓치지 않게)")
    void judgesWithoutSexWhenBothSexesAgree() {
        assertThat(criteria.judge(ItemCode.GAMMA_GTP, "196", UNKNOWN).judgment()).isEqualTo(Judgment.SUSPECT);
        assertThat(criteria.judge(ItemCode.WAIST_CIRCUMFERENCE, "95", UNKNOWN).judgment()).isEqualTo(Judgment.SUSPECT);
        assertThat(criteria.judge(ItemCode.WAIST_CIRCUMFERENCE, "80", null).judgment()).isEqualTo(Judgment.NORMAL_A);
    }

    @Test
    @DisplayName("판정에는 근거가 된 기준 표의 행과 출처(고시 번호)가 붙는다 (가드레일 원칙 6)")
    void attachesCriterionAndSource() {
        CriteriaJudgment judgment = criteria.judge(ItemCode.FASTING_GLUCOSE, "126", FEMALE);

        assertThat(judgment.criterion()).contains("공복 혈당", "126 이상");
        assertThat(judgment.source()).contains("검사항목별 판정기준", "보건복지부고시 제2026-6호");
    }

    @ParameterizedTest(name = "{0} {1} [{2}] → {3}")
    @DisplayName("표기 변형: 높음·낮음 표시, 쉼표, 같은 단위의 다른 표기는 떼고 판정한다 (ADR 0007 보완)")
    @CsvSource(delimiter = '|', value = {
            "FASTING_GLUCOSE | 140 H | mg/dL | SUSPECT", "FASTING_GLUCOSE | H 140 | mg/dL | SUSPECT",
            "FASTING_GLUCOSE | 140▲ | mg/dL | SUSPECT", "HEMOGLOBIN | 9.3▼ | g/dL | SUSPECT",
            "HEMOGLOBIN | 9.3 (L) | g/dL | SUSPECT", "TRIGLYCERIDES | 1,120 | mg/dL | SUSPECT",
            "FASTING_GLUCOSE | 93 | ㎎/dL | NORMAL_A", "FASTING_GLUCOSE | 93 | mg/dl | NORMAL_A",
            "FASTING_GLUCOSE | 93 | mg/㎗ | NORMAL_A", "AST | 21 | IU/L | NORMAL_A", "AST | 21 | U/l | NORMAL_A",
            "EGFR | 40 | ml/min/1.73㎡ | SUSPECT", "EGFR | 40 | mL/min/1.73m2 | SUSPECT", "BMI | 31 | kg/m2 | SUSPECT",
            "FASTING_GLUCOSE | 93 | | NORMAL_A"
    })
    void judgesNotationVariants(ItemCode code, String value, String unit, Judgment expected) {
        assertThat(criteria.judge(code, value, unit, MALE).judgment()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {1} [{2}] → 판정하지 않음: {3}")
    @DisplayName("모르는 표기: 다른 단위, 부등호, 읽지 못한 값은 판정하지 않고 이유를 남긴다 (환산하지 않는다)")
    @CsvSource(delimiter = '|', value = {
            "FASTING_GLUCOSE | 7.2 | mmol/L | 단위가 다름", "FASTING_GLUCOSE | 22.4 | mmol/L | 단위가 다름",
            "SERUM_CREATININE | 88 | μmol/L | 단위가 다름", "HEMOGLOBIN | 135 | g/L | 단위가 다름",
            "EGFR | >90 | mL/min/1.73m² | 실제 수치가 아닌 값", "TRIGLYCERIDES | 400 이상 | mg/dL | 실제 수치가 아닌 값",
            "FASTING_GLUCOSE | 검사 안 함 | mg/dL | 숫자로 읽지 못함", "FASTING_GLUCOSE | 140 HDL | mg/dL | 숫자로 읽지 못함",
            "FASTING_GLUCOSE | 7.2 L/min | mg/dL | 숫자로 읽지 못함", "FASTING_GLUCOSE | HDL 140 | mg/dL | 숫자로 읽지 못함", "FASTING_GLUCOSE | 100~125 | mg/dL | 숫자로 읽지 못함",
            "HEMOGLOBIN | 16.6 | g/dL | 판정기준표에 없는 구간"
    })
    void doesNotJudgeUnknownNotation(ItemCode code, String value, String unit, String note) {
        CriteriaJudgment judgment = criteria.judge(code, value, unit, MALE);
        assertThat(judgment.judgment()).isEqualTo(Judgment.NOT_JUDGED);
        assertThat(judgment.note()).startsWith(note);
        assertThat(judgment.unjudgedTarget()).isTrue();
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @DisplayName("글자 표기 변형: 아는 표기만 판정한다. 흉부방사선 질환의심은 '의심'·'소견서 참조'처럼 명시된 표기일 때만이다")
    @CsvSource(delimiter = '|', value = {
            "URINE_PROTEIN | 음성(-) | NORMAL_A", "URINE_PROTEIN | Negative | NORMAL_A", "URINE_PROTEIN | - | NORMAL_A",
            "URINE_PROTEIN | (-) | NORMAL_A", "URINE_PROTEIN | ± | NORMAL_B", "URINE_PROTEIN | +/- | NORMAL_B",
            "URINE_PROTEIN | trace | NORMAL_B", "URINE_PROTEIN | Trace | NORMAL_B", "URINE_PROTEIN | 1+ | SUSPECT",
            "URINE_PROTEIN | +1 | SUSPECT", "URINE_PROTEIN | 양성(+) | SUSPECT", "URINE_PROTEIN | 3+ | SUSPECT",
            "URINE_PROTEIN | 검사 불가 | NOT_JUDGED",
            "CHEST_XRAY | 정상(특이소견 없음) | NORMAL_A", "CHEST_XRAY | 정상A | NORMAL_A", "CHEST_XRAY | 특이소견 없음 | NORMAL_A",
            "CHEST_XRAY | 이상 소견 없음 | NORMAL_A", "CHEST_XRAY | 결핵 의심 소견 없음 | NORMAL_A",
            "CHEST_XRAY | 비활동성 폐결핵(추적 관찰) | NORMAL_B", "CHEST_XRAY | 폐결절 의심 | SUSPECT",
            "CHEST_XRAY | 활동성 폐결핵 의심 | SUSPECT", "CHEST_XRAY | 심비대 소견 | SUSPECT", "CHEST_XRAY | 우상엽 결절 | SUSPECT",
            "CHEST_XRAY | 결절 소견 없음 | NORMAL_A", "CHEST_XRAY | 대동맥 석회화 | NOT_JUDGED",
            "CHEST_XRAY | 비활동성 결핵 흔적 | NOT_JUDGED", "CHEST_XRAY | 사진불량(재촬영 의심) | NOT_JUDGED"
    })
    void judgesKnownTextNotationsOnly(ItemCode code, String value, Judgment expected) {
        CriteriaJudgment judgment = criteria.judge(code, value, null, MALE);
        assertThat(judgment.judgment()).isEqualTo(expected);
        assertThat(judgment.unjudgedTarget()).isEqualTo(expected == Judgment.NOT_JUDGED);
    }

    private static OptionalDouble bsa(double heightCm, double weightKg) {
        return ScreeningCriteria.bodySurfaceArea(List.of(
                new ReportItem(ItemCode.HEIGHT, "신장", String.valueOf(heightCm), "cm", null, null),
                new ReportItem(ItemCode.WEIGHT, "체중", String.valueOf(weightKg), "kg", null, null)));
    }

    @Test
    @DisplayName("체표면적은 결과지의 신장·체중으로 구한다 (Du Bois). 하나라도 없거나 단위가 다르면 구하지 않는다")
    void bodySurfaceAreaFromHeightAndWeight() {
        // 170cm, 63kg은 약 1.73m² (기준 체표면적 근처)
        assertThat(bsa(170, 63).getAsDouble()).isCloseTo(1.73, within(0.02));
        assertThat(bsa(185, 100).getAsDouble()).isCloseTo(2.24, within(0.03));

        assertThat(ScreeningCriteria.bodySurfaceArea(List.of(
                new ReportItem(ItemCode.HEIGHT, "신장", "170", "cm", null, null)))).isEmpty();
        assertThat(ScreeningCriteria.bodySurfaceArea(List.of(
                new ReportItem(ItemCode.HEIGHT, "신장", "1.7", "m", null, null),
                new ReportItem(ItemCode.WEIGHT, "체중", "63", "kg", null, null)))).isEmpty();
        assertThat(ScreeningCriteria.bodySurfaceArea(null)).isEmpty();
    }

    @Test
    @DisplayName("eGFR이 mL/min이면 보정값·절대값 두 해석의 판정이 같을 때만 판정하고, 그 사실을 돌려준다")
    void judgesUnindexedEgfrOnlyWhenBothReadingsAgree() {
        // 94.5: 보정값이면 94.5, 절대값이면 172cm·68kg(약 1.80m²)에서 약 91. 둘 다 정상A
        CriteriaJudgment normal = criteria.judge(ItemCode.EGFR, "94.5", "mL/min", MALE, bsa(172.1, 68.1));
        assertThat(normal.judgment()).isEqualTo(Judgment.NORMAL_A);
        assertThat(normal.unitNote()).contains("mL/min").contains("판정이 같아 판정");
        assertThat(normal.note()).isNull();

        // 40: 체격이 작아(150cm·45kg, 약 1.37m²) 절대값이라 해도 약 50. 둘 다 질환의심
        CriteriaJudgment suspect = criteria.judge(ItemCode.EGFR, "40", "ml / min", MALE, bsa(150, 45));
        assertThat(suspect.judgment()).isEqualTo(Judgment.SUSPECT);
        assertThat(suspect.unitNote()).isNotNull();
    }

    @Test
    @DisplayName("eGFR mL/min: 두 해석의 판정이 다르면 판정하지 않는다 (체격이 큰 사람의 거짓 안심을 막는다)")
    void doesNotJudgeUnindexedEgfrWhenReadingsDisagree() {
        // 65: 보정값이면 정상A. 절대값이면 185cm·100kg(약 2.24m²)에서 약 50, 질환의심
        CriteriaJudgment large = criteria.judge(ItemCode.EGFR, "65", "mL/min", MALE, bsa(185, 100));
        assertThat(large.judgment()).isEqualTo(Judgment.NOT_JUDGED);
        assertThat(large.note()).contains("1.73m² 보정 표기가 없음").contains("판정이 달라짐");
        assertThat(large.unitNote()).isNull();
        assertThat(large.unjudgedTarget()).isTrue();

        // 55: 보정값이면 질환의심. 절대값이면 150cm·45kg에서 약 69, 정상A
        assertThat(criteria.judge(ItemCode.EGFR, "55", "mL/min", MALE, bsa(150, 45)).judgment())
                .isEqualTo(Judgment.NOT_JUDGED);
    }

    @Test
    @DisplayName("eGFR mL/min: 신장·체중이 없으면 판정하지 않는다. 대표 단위에는 영향이 없고 다른 항목의 mL/min은 모르는 단위다")
    void unindexedEgfrNeedsBodySizeAndStaysNarrow() {
        CriteriaJudgment noBody = criteria.judge(ItemCode.EGFR, "94.5", "mL/min", MALE);
        assertThat(noBody.judgment()).isEqualTo(Judgment.NOT_JUDGED);
        assertThat(noBody.note()).contains("신장·체중이 없어");

        CriteriaJudgment canonical = criteria.judge(ItemCode.EGFR, "65", "mL/min/1.73m²", MALE, bsa(185, 100));
        assertThat(canonical.judgment()).isEqualTo(Judgment.NORMAL_A);
        assertThat(canonical.unitNote()).isNull();

        assertThat(criteria.judge(ItemCode.SERUM_CREATININE, "0.9", "mL/min", MALE, bsa(170, 63)).judgment())
                .isEqualTo(Judgment.NOT_JUDGED);
        assertThat(criteria.judge(ItemCode.EGFR, "1.5", "mL/s", MALE, bsa(170, 63)).judgment())
                .isEqualTo(Judgment.NOT_JUDGED);

        // 부등호 값은 체격이 있어도 판정하지 않는다
        CriteriaJudgment inequality = criteria.judge(ItemCode.EGFR, ">90", "mL/min", MALE, bsa(170, 63));
        assertThat(inequality.judgment()).isEqualTo(Judgment.NOT_JUDGED);
        assertThat(inequality.note()).startsWith("실제 수치가 아닌 값");
    }

    @Test
    @DisplayName("결과지 항목으로 판정하면 성별과 체격을 결과지에서 가져온다")
    void judgesFromReport() {
        ReportItem egfr = new ReportItem(ItemCode.EGFR, "신사구체여과율", "94.5", "mL/min", null, null);
        CheckupReport report = new CheckupReport(null, null, MALE, null, List.of(
                new ReportItem(ItemCode.HEIGHT, "신장", "172.1", "cm", null, null),
                new ReportItem(ItemCode.WEIGHT, "체중", "68.1", "kg", null, null), egfr));
        assertThat(criteria.judge(egfr, report).judgment()).isEqualTo(Judgment.NORMAL_A);
        assertThat(criteria.judge(egfr, null).judgment()).isEqualTo(Judgment.NOT_JUDGED);
    }

    @Test
    @DisplayName("판정기준표에 없는 항목은 판정 대상이 아니다 (확인 필요로 알리지 않는다)")
    void itemsOutsideTheTableAreNotTargets() {
        assertThat(criteria.judge(ItemCode.VISION_LEFT, "1.0", null, MALE).unjudgedTarget()).isFalse();
        assertThat(criteria.judge(ItemCode.OTHER, "5.2", "%", MALE).unjudgedTarget()).isFalse();
    }
}
