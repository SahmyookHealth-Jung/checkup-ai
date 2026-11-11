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

    /** 참고치가 부등호 값인 줄. 모델이 참고치 "<120"을 값으로 옮기는 실수를 시험한다 */
    private static final String BP_SOURCE = "수축기혈압 | 118 | mmHg | <120";

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
    @DisplayName("숫자가 없는 값은 버리고, 생리적으로 불가능한 값은 결과에서 빼 확인 필요로 넘긴다")
    void dropsImplausibleNumbers() {
        ValidationResult result = validate(
                item(ItemCode.HEMOGLOBIN, "FBS", "183"),       // 혈색소 183은 불가능: 다른 행을 읽은 것
                item(ItemCode.FASTING_GLUCOSE, "FBS", "정상"));   // 숫자 항목에 숫자가 없는 값

        assertThat(result.report().items()).isEmpty();
        assertThat(types(result)).containsOnly(IssueType.IMPLAUSIBLE);
        assertThat(result.needsReview()).extracting(ReviewItem::code, ReviewItem::value)
                .containsExactly(tuple(ItemCode.HEMOGLOBIN, "183"));
    }

    @Test
    @DisplayName("표시·쉼표가 붙은 숫자와 부등호 값은 원문 표기 그대로 남긴다. 판정 코드가 같은 규칙으로 읽는다 (ADR 0004 보완)")
    void keepsDecoratedNumbersAsWritten() {
        String source = """
                신사구체여과율 | >90 | mL/min/1.73m² | 60 이상
                혈색소 | 9.3▼ | g/dL | 12.0~15.5
                크레아티닌 | 0.8 | mg/dL
                총콜레스테롤 | 1,120 | mg/dL
                중성지방 | 64 L | mg/dL
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.FEMALE, 50,
                List.of(item(ItemCode.EGFR, "신사구체여과율", ">90"),
                        item(ItemCode.HEMOGLOBIN, "혈색소", "9.3▼"),
                        item(ItemCode.TOTAL_CHOLESTEROL, "총콜레스테롤", "1,120"),   // 표시를 뗀 1120으로 범위 검사
                        item(ItemCode.TRIGLYCERIDES, "중성지방", "64 L")));

        ValidationResult result = validator.validate(report, source);

        // 부등호 값은 읽히는 값보다 뒤에 검사하므로 결과의 끝에 온다
        assertThat(result.report().items()).extracting(ReportItem::code, ReportItem::value)
                .containsExactly(tuple(ItemCode.HEMOGLOBIN, "9.3▼"), tuple(ItemCode.TRIGLYCERIDES, "64 L"),
                        tuple(ItemCode.EGFR, ">90"));
        assertThat(result.needsReview()).singleElement().satisfies(review -> {
            assertThat(review.code()).isEqualTo(ItemCode.TOTAL_CHOLESTEROL);
            assertThat(review.value()).isEqualTo("1,120");
            assertThat(review.reason()).contains("가능 범위 밖");
        });
    }

    @Test
    @DisplayName("숫자가 들어 있어도 두 모양(표시 붙은 숫자, 부등호 값)이 아니면 버린다 (2026-11-11 Fable 리뷰)")
    void dropsOtherShapesWithDigits() {
        String source = """
                혈압 | 150/95 | mmHg
                체중 | 650kg
                혈색소 | 9.1 | g/dL | 13.0~16.5
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, 50,
                List.of(item(ItemCode.SYSTOLIC_BP, "혈압", "150/95"),
                        item(ItemCode.WEIGHT, "체중", "650kg"),          // 판정 대상이 아니라 남기면 경고 없이 표시된다
                        item(ItemCode.HEMOGLOBIN, "혈색소", "13.0~16.5")));  // 참고치를 값으로 옮김

        ValidationResult result = validator.validate(report, source);

        assertThat(result.report().items()).isEmpty();
        assertThat(result.issues()).extracting(Issue::type, Issue::detail).containsExactly(
                tuple(IssueType.IMPLAUSIBLE, "숫자가 아님: 150/95"), tuple(IssueType.IMPLAUSIBLE, "숫자가 아님: 650kg"),
                tuple(IssueType.IMPLAUSIBLE, "숫자가 아님: 13.0~16.5"));
        assertThat(result.needsReview()).isEmpty();
    }

    @Test
    @DisplayName("부등호 값이 먼저 와도 같은 코드의 읽히는 값을 남긴다 (참고치 \"<120\"이 실제 결과 118을 밀어내지 않게)")
    void readableValueBeatsInequality() {
        ValidationResult result = validator.validate(new CheckupReport(null, CheckupReport.ExamType.GENERAL,
                CheckupReport.Sex.MALE, 50, List.of(item(ItemCode.SYSTOLIC_BP, "수축기혈압", "<120"),
                        item(ItemCode.SYSTOLIC_BP, "수축기혈압", "118"))), BP_SOURCE);

        assertThat(result.report().items()).extracting(ReportItem::value).containsExactly("118");
        assertThat(result.issues()).extracting(Issue::type, Issue::detail)
                .contains(tuple(IssueType.CONFLICT, "같은 코드에 다른 값: <120"));
    }

    @Test
    @DisplayName("부등호 값은 결과 줄을 차지하지 않아 그 줄이 누락 의심으로 남고, 재요청이 가져온 읽히는 값이 부등호 값을 대신한다")
    void inequalityDoesNotClaimLineAndYieldsToRetry() {
        ValidationResult first = validator.validate(new CheckupReport(null, CheckupReport.ExamType.GENERAL,
                CheckupReport.Sex.MALE, 50, List.of(item(ItemCode.SYSTOLIC_BP, "수축기혈압", "<120"))), BP_SOURCE);

        assertThat(first.report().items()).extracting(ReportItem::value).containsExactly("<120");
        assertThat(first.uncoveredSegments()).singleElement().asString().contains("118");

        ValidationResult merged = validator.validateAdditional(first.report(),
                List.of(item(ItemCode.SYSTOLIC_BP, "수축기혈압", "118")), BP_SOURCE);

        assertThat(merged.report().items()).extracting(ReportItem::value).containsExactly("118");
        assertThat(merged.issues()).extracting(Issue::type).containsExactly(IssueType.CONFLICT);
        assertThat(merged.uncoveredSegments()).isEmpty();
    }

    @Test
    @DisplayName("원문에 있는 범위 밖 수치(공복혈당 1120)는 결과에서 빼되 버리지 않고 확인 필요 항목으로 남긴다")
    void keepsOutOfRangeValueForReview() {
        String source = "공복혈당 | 1120 | mg/dL | 100 미만";
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, 50,
                List.of(item(ItemCode.FASTING_GLUCOSE, "공복혈당", "1120")));

        ValidationResult result = validator.validate(report, source);

        assertThat(result.report().items()).isEmpty();
        assertThat(result.needsReview()).singleElement().satisfies(review -> {
            assertThat(review.code()).isEqualTo(ItemCode.FASTING_GLUCOSE);
            assertThat(review.value()).isEqualTo("1120");
            assertThat(review.reason()).contains("가능 범위 밖");
        });
    }

    @Test
    @DisplayName("OTHER 항목은 항목명과 값이 같은 줄에 있어야 남긴다 (다른 줄 값을 빌린 가짜 항목 제거)")
    void dropsOtherItemWhoseNameAndValueAreOnDifferentLines() {
        ValidationResult result = validate(
                item(ItemCode.OTHER, "HbA1c", "5.1"),     // 같은 줄: 남김
                item(ItemCode.OTHER, "참조) 정상", "5.1"),  // "참조) 정상"은 원문에 없음: 버림
                item(ItemCode.OTHER, "Chest PA", "5.1"));  // 이름은 있지만 다른 줄: 버림

        assertThat(result.report().items()).extracting(ReportItem::rawName).containsExactly("HbA1c");
        assertThat(types(result)).containsOnly(IssueType.OTHER_NAME_VALUE_APART);
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
    @DisplayName("판정 칸이 없는 결과지에서 모델이 지어낸 판정('정상')을 지운다 (2026-11-09 실측 사례)")
    void clearsInventedJudgment() {
        String noJudgmentColumn = """
                검사항목 | 결과 | 단위 | 참고치
                공복혈당 | 112 | mg/dL | 100 미만
                요단백 | 음성 | - | 음성
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.FEMALE, 51,
                List.of(new ReportItem(ItemCode.FASTING_GLUCOSE, "공복혈당", "112", "mg/dL", "100 미만", "정상"),
                        new ReportItem(ItemCode.URINE_PROTEIN, "요단백", "음성", "-", "음성", "정상")));

        ValidationResult result = validator.validate(report, noJudgmentColumn);

        assertThat(result.report().items()).extracting(ReportItem::centerJudgment).containsOnlyNulls();
        assertThat(result.report().items().get(1).unit()).as("'-'는 단위 없음").isNull();
        assertThat(types(result)).containsExactly(IssueType.UNGROUNDED_FIELD, IssueType.UNGROUNDED_FIELD);
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

        assertThat(result.uncoveredSegments()).hasSize(2);
        assertThat(result.uncoveredSegments().get(0)).startsWith("Chest PA");
        assertThat(result.uncoveredSegments().get(1)).startsWith("HbA1c");
    }

    @Test
    @DisplayName("한 줄에 여러 항목이 있으면 쉼표 조각 단위로 누락을 찾는다 (v1은 줄 단위라 놓쳤다)")
    void findsMissingItemsWithinMultiItemLine() {
        String narrative = """
                · 시력 좌 0.7, 시력 우 0.8, 청력 좌 비정상, 청력 우 정상
                · 공복 혈당 206 mg/dL (참고 100 미만) → 질환의심
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, null,
                List.of(item(ItemCode.HEARING_LEFT, "청력 좌", "비정상"),
                        item(ItemCode.HEARING_RIGHT, "청력 우", "정상"),
                        item(ItemCode.FASTING_GLUCOSE, "공복 혈당", "206")));

        ValidationResult result = validator.validate(report, narrative);

        assertThat(result.uncoveredSegments()).containsExactly("· 시력 좌 0.7", "시력 우 0.8");
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

    @Test
    @DisplayName("PDF 표 칸에서 두 줄로 줄바꿈된 값: 둘째 줄을 누락으로 보지 않는다. 판정은 다른 열과 구분할 수 없어 지운다 (std-004 실측)")
    void treatsValueWrappedOverTwoLinesAsOneCell() {
        String wrapped = """
                             혈액검사                 감마지티피                 76                  U/L                  11~63               정상B(경계)
                             영상검사                 흉부방사선                 기타  질환   의심(소
                                                                        견서  참조)             -                    -                   질환의심
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, 45,
                List.of(new ReportItem(ItemCode.GAMMA_GTP, "감마지티피", "76", "U/L", "11~63", "정상B(경계)"),
                        new ReportItem(ItemCode.CHEST_XRAY, "흉부방사선", "기타 질환 의심(소견서 참조)", null, null, "질환의심")));

        ValidationResult result = validator.validate(report, wrapped);

        assertThat(result.report().items()).extracting(ReportItem::code)
                .containsExactly(ItemCode.GAMMA_GTP, ItemCode.CHEST_XRAY);
        assertThat(result.report().items().get(1).centerJudgment()).as("틀린 판정보다 빈 판정이 덜 위험하다").isNull();
        assertThat(types(result)).containsExactly(IssueType.UNGROUNDED_FIELD);
        assertThat(result.uncoveredSegments()).isEmpty();
    }

    @Test
    @DisplayName("줄바꿈된 값의 둘째 줄에 다른 열의 결과어가 있어도 누락 조각으로 재요청하지 않는다 (sum 템플릿의 '참조) 정상')")
    void doesNotRetryContinuationLineOfWrappedValue() {
        String wrapped = """
                 Chest PA                 기타 질환 의심(소견서
                                          참조)                    정상
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.COMPREHENSIVE, CheckupReport.Sex.FEMALE,
                42, List.of(item(ItemCode.CHEST_XRAY, "Chest PA", "기타 질환 의심(소견서 참조)")));

        assertThat(validator.validate(report, wrapped).uncoveredSegments()).isEmpty();
    }

    @Test
    @DisplayName("OTHER 항목도 같은 항목명에 값이 여럿이면 처음 값만 남긴다 (sum-003: 한 줄의 금회·전회·참고치를 각각 항목으로 뽑음)")
    void keepsFirstValueOfSameOtherName() {
        String source = "T-Bil      0.8      0.7      mg/dL      0.2-1.2";
        CheckupReport empty = new CheckupReport(null, CheckupReport.ExamType.COMPREHENSIVE, CheckupReport.Sex.MALE, 50,
                List.of());

        ValidationResult merged = validator.validateAdditional(empty, List.of(
                item(ItemCode.OTHER, "T-Bil", "0.8"),
                item(ItemCode.OTHER, "T-Bil", "0.7"),
                item(ItemCode.OTHER, "T-Bil", "0.2-1.2"),
                item(ItemCode.OTHER, "T-Bil", "0.8")), source);

        assertThat(merged.report().items()).extracting(ReportItem::value).containsExactly("0.8");
        assertThat(types(merged)).containsExactly(IssueType.CONFLICT, IssueType.CONFLICT, IssueType.DUPLICATE);
    }

    @Test
    @DisplayName("참고치 칸에 들어온 판정 등급(정상A 등)은 지운다. 요단백의 '음성' 같은 글자 참고치는 남긴다 (nar 실측)")
    void clearsJudgmentGradeInReference() {
        String narrative = """
                · 키 173.0cm, 몸무게 68.5kg, 체질량지수 22.9kg/m² (정상A), 허리둘레 87cm (정상A)
                · 요단백 음성 (참고 음성) → 정상A
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, null,
                List.of(new ReportItem(ItemCode.BMI, "체질량지수", "22.9", "kg/m²", "정상A", "정상A"),
                        new ReportItem(ItemCode.URINE_PROTEIN, "요단백", "음성", null, "음성", "정상A")));

        ValidationResult result = validator.validate(report, narrative);

        assertThat(result.report().items()).extracting(ReportItem::referenceRaw).containsExactly(null, "음성");
        assertThat(result.report().items().getFirst().centerJudgment()).isEqualTo("정상A");
        assertThat(result.issues()).extracting(Issue::type, Issue::detail)
                .containsExactly(tuple(IssueType.MISPLACED_FIELD, "referenceRaw=정상A (판정 등급)"));
    }

    @Test
    @DisplayName("검진일이 있는 줄, 전화번호, 영문 식별자는 결과로 보지 않는다 (heldout-v1: 등록번호·채혈일·문의 줄을 매번 재요청)")
    void ignoresSubjectInfoAndNonResultNumbers() {
        String lab = """
                등록번호: SYN-50520    성별/나이: 여/35    채혈일: 2026/09/16
                공복혈당(FBS) | 73 | 정상 | 100 미만 | mg/dL
                고객 코드 AB-1234
                문의: 진단검사의학과 000-0000-0000
                """;
        CheckupReport report = new CheckupReport(java.time.LocalDate.of(2026, 9, 16), CheckupReport.ExamType.UNKNOWN,
                CheckupReport.Sex.FEMALE, 35, List.of(item(ItemCode.FASTING_GLUCOSE, "공복혈당(FBS)", "73")));

        assertThat(validator.validate(report, lab).uncoveredSegments()).isEmpty();
    }

    @Test
    @DisplayName("다른 항목의 같은 값이 그 줄을 덮지 않는다: 항목명이 없는 조각은 차지하지 않는다 (heldout-v1·sum-008 실측)")
    void sameValueOfAnotherItemDoesNotCoverLine() {
        String source = """
                청력(좌/우) | 정상/정상
                흉부 X-ray | 정상 | 정상
                HDL-C | 74 | mg/dL
                DBP | 74 | mmHg
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.UNKNOWN, CheckupReport.Sex.MALE, 50,
                List.of(item(ItemCode.CHEST_XRAY, "흉부 X-ray", "정상"), item(ItemCode.HDL_CHOLESTEROL, "HDL-C", "74")));

        assertThat(validator.validate(report, source).uncoveredSegments())
                .containsExactly("청력(좌/우) | 정상/정상", "DBP | 74 | mmHg");
    }

    @Test
    @DisplayName("같은 값을 다시 말하는 소견 문장은 항목명이 있으면 그 항목이 차지한다 (nar 의사 소견)")
    void commentRepeatingValueIsCoveredByItsItem() {
        String narrative = """
                · 공복 혈당 206 mg/dL (참고 100 미만) → 질환의심
                공복 혈당 수치는 지난 검진 때 230 mg/dL에서 이번에 206 mg/dL로 변했습니다. 생활습관을 점검하시고
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, null,
                List.of(item(ItemCode.FASTING_GLUCOSE, "공복 혈당", "206")));

        assertThat(validator.validate(report, narrative).uncoveredSegments()).isEmpty();
    }

    @Test
    @DisplayName("항목명이 원문에 없는 항목은 값이 있는 조각 중 아직 아무도 차지하지 않은 것을 차지한다")
    void itemWithoutSourceNameClaimsUnclaimedSegment() {
        String source = """
                HDL-C | 74 | mg/dL
                DBP | 74 | mmHg
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.UNKNOWN, CheckupReport.Sex.MALE, 50,
                List.of(item(ItemCode.DIASTOLIC_BP, "이완기 혈압", "74"), item(ItemCode.HDL_CHOLESTEROL, "HDL-C", "74")));

        assertThat(validator.validate(report, source).uncoveredSegments()).isEmpty();
    }

    @Test
    @DisplayName("OTHER 항목명이 줄바꿈돼 윗줄에 이름만 있으면 이어서 본다. 결과어가 있는 윗줄은 잇지 않는다 (v2 가짜 항목은 계속 막음)")
    void acceptsOtherNameWrappedOntoPreviousLine() {
        String source = """
                Chest PA                 기타 질환 의심(소견서
                                         참조)                    정상
                TSH      3.88     4.18     μIU/mL     0.4-4.5
                고감도 C반응단백
                (hs-CRP)     0.8     1.0 미만     mg/L
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.COMPREHENSIVE, CheckupReport.Sex.MALE,
                50, List.of(item(ItemCode.CHEST_XRAY, "Chest PA", "기타 질환 의심(소견서 참조)"),
                        item(ItemCode.OTHER, "참조) 정상", "3.88"),
                        item(ItemCode.OTHER, "고감도 C반응단백", "0.8")));

        ValidationResult result = validator.validate(report, source);

        assertThat(result.report().items()).extracting(ReportItem::rawName).containsExactly("Chest PA", "고감도 C반응단백");
        assertThat(result.issues()).extracting(Issue::type, Issue::detail)
                .containsExactly(tuple(IssueType.OTHER_NAME_VALUE_APART, "참조) 정상=3.88"));
        assertThat(result.uncoveredSegments()).containsExactly("TSH 3.88 4.18 μIU/mL 0.4-4.5");
    }

    @Test
    @DisplayName("줄 전체를 옮긴 항목은 버린다: 숫자 여러 개가 뭉친 값, 값이 들어 있는 OTHER 항목명 (heldout-v2 재요청 실측)")
    void dropsItemsCopiedFromWholeLine() {
        String trend = """
                건강검진 3개년 추이 보고서
                알부민 g/dL 3.8 4.1 4.8 3.5~5.2
                시력: 좌 0.5 우 0.9 / 청력: 좌 정상 우 정상
                상담 문의 1588-0000 (평일 09:00~18:00)
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.COMPREHENSIVE, CheckupReport.Sex.MALE,
                50, List.of(item(ItemCode.OTHER, "건강검진 3개년 추이 보고서", "건강검진 3개년 추이 보고서"),
                        item(ItemCode.OTHER, "알부민", "3.8 4.1 4.8 3.5~5.2"),
                        item(ItemCode.OTHER, "시력: 좌 0.5 우 0.9 / 청력: 좌 정상 우 정상", "0.5"),
                        item(ItemCode.OTHER, "상담 문의 1588-0000 (평일 09:00~18:00)", "1588-0000"),
                        item(ItemCode.VISION_LEFT, "시력: 좌 0.5 우 0.9", "0.5")));  // 표준 코드는 코드가 정체: 남긴다

        ValidationResult result = validator.validate(report, trend);

        assertThat(result.report().items()).extracting(ReportItem::code).containsExactly(ItemCode.VISION_LEFT);
        assertThat(types(result)).containsExactly(IssueType.OTHER_NAME_HOLDS_VALUE, IssueType.BUNDLED_VALUES,
                IssueType.OTHER_NAME_HOLDS_VALUE, IssueType.OTHER_NAME_HOLDS_VALUE);
    }

    @Test
    @DisplayName("이름 속 숫자, 괄호 속 보조 수치, 공백 낀 범위는 줄을 옮긴 것으로 보지 않는다 (HbA1c, 25-OH 비타민D, 1+ (30), 0 - 2)")
    void keepsNamesWithDigitsAndAnnotatedValues() {
        String source = """
                HbA1c    1    %
                25-OH 비타민D    25    ng/mL
                요단백    1+ (30)    mg/dL
                적혈구(요침사)    0 - 2    /HPF
                """;
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.COMPREHENSIVE, CheckupReport.Sex.MALE,
                50, List.of(item(ItemCode.OTHER, "HbA1c", "1"),
                        item(ItemCode.OTHER, "25-OH 비타민D", "25"),
                        item(ItemCode.OTHER, "요단백", "1+ (30)"),
                        item(ItemCode.OTHER, "적혈구(요침사)", "0 - 2")));

        ValidationResult result = validator.validate(report, source);

        assertThat(result.report().items()).hasSize(4);
        assertThat(types(result)).doesNotContain(IssueType.BUNDLED_VALUES, IssueType.OTHER_NAME_HOLDS_VALUE);
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
