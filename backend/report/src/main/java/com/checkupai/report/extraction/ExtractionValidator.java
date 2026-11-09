package com.checkupai.report.extraction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 추출 결과를 원문과 대조해 고치고, 빠진 것으로 보이는 줄을 찾는다 (ADR 0003, 6단계).
 *
 * <p>LLM을 부르지 않는 결정적(deterministic) 검사만 한다. 재요청 여부는 {@link ReportExtractor}가 정한다.
 * <ol>
 *   <li>중복 제거: 같은 코드·같은 값이 여러 번 나오면 하나만 남긴다 (기준선에서 관찰된 반복 오류)</li>
 *   <li>값 원문 대조: 원문에 없는 값은 버린다 (지어낸 값 차단)</li>
 *   <li>OTHER 항목: 항목명과 값이 같은 줄에 없으면 버린다 (다른 항목 값을 빌려 만든 가짜 항목 차단)</li>
 *   <li>숫자 항목 상식 검사: 숫자가 아니거나 생리적으로 불가능한 값은 버린다</li>
 *   <li>같은 코드(OTHER는 같은 항목명)에 다른 값이 여럿이면 처음 것만 남긴다</li>
 *   <li>참고치 칸에 들어온 판정 등급(정상A 등)은 지운다</li>
 *   <li>필드 원문 대조: 값이 있는 줄에 없는 단위·참고치·판정은 null로 바꾼다</li>
 *   <li>커버리지: 검사 결과처럼 보이지만 아무 값도 추출되지 않은 줄 조각을 모은다 (누락 의심)</li>
 * </ol>
 * PDF 표 칸에서 줄바꿈되어 두 줄에 걸친 값은 누락 조각 검사와 OTHER 항목명 확인에서 두 줄을 함께 본다
 * ({@link SourceText#valueWindows}). 필드 원문 대조에서는 보지 않는다(이유는 groundFields 주석).
 */
@Component
public class ExtractionValidator {

    /**
     * 생리적으로 가능한 넓은 범위. 판정 기준이 아니라 "읽기 오류" 탐지용이다.
     * 예: 공복혈당 1120은 소수점·자릿수를 잘못 읽은 것으로 본다.
     */
    private static final Map<ItemCode, double[]> PLAUSIBLE = new EnumMap<>(Map.ofEntries(
            Map.entry(ItemCode.HEIGHT, new double[] {100, 230}),
            Map.entry(ItemCode.WEIGHT, new double[] {20, 250}),
            Map.entry(ItemCode.WAIST_CIRCUMFERENCE, new double[] {40, 200}),
            Map.entry(ItemCode.BMI, new double[] {10, 70}),
            Map.entry(ItemCode.SYSTOLIC_BP, new double[] {60, 260}),
            Map.entry(ItemCode.DIASTOLIC_BP, new double[] {30, 160}),
            Map.entry(ItemCode.VISION_LEFT, new double[] {0, 2.5}),
            Map.entry(ItemCode.VISION_RIGHT, new double[] {0, 2.5}),
            Map.entry(ItemCode.HEMOGLOBIN, new double[] {3, 25}),
            Map.entry(ItemCode.FASTING_GLUCOSE, new double[] {20, 800}),
            Map.entry(ItemCode.TOTAL_CHOLESTEROL, new double[] {50, 600}),
            Map.entry(ItemCode.HDL_CHOLESTEROL, new double[] {5, 200}),
            Map.entry(ItemCode.TRIGLYCERIDES, new double[] {10, 3000}),
            Map.entry(ItemCode.LDL_CHOLESTEROL, new double[] {10, 500}),
            Map.entry(ItemCode.SERUM_CREATININE, new double[] {0.1, 20}),
            Map.entry(ItemCode.EGFR, new double[] {1, 200}),
            Map.entry(ItemCode.AST, new double[] {1, 5000}),
            Map.entry(ItemCode.ALT, new double[] {1, 5000}),
            Map.entry(ItemCode.GAMMA_GTP, new double[] {1, 5000})));

    /** 수검자 정보 줄. 검사 결과가 아니므로 커버리지 검사에서 뺀다. */
    private static final Pattern META_LINE = Pattern.compile("수검번호|성명|이름|생년월일|검진일|검사일|주민|연락처|주소|※");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])\\d+(\\.\\d+)?(?![\\d])");
    private static final Pattern DATE = Pattern.compile("\\d{4}\\s*[-./년]\\s*\\d{1,2}\\s*[-./월]\\s*\\d{1,2}");
    private static final Pattern RESULT_WORD = Pattern.compile("음성|양성|정상|비정상");
    private static final Pattern EMPTY_MARK = Pattern.compile("[-–—]+|없음|N/?A", Pattern.CASE_INSENSITIVE);

    /**
     * 국가건강검진 판정 등급 (정상A, 정상B(경계), 질환의심, 유질환자). 판정 칸의 값이지 참고치가 될 수 없다.
     * 2026-11-09 전체 평가: 서술형 "체질량지수 22.9kg/m² (정상A)"의 괄호 속 판정을 참고치로 읽은 경우가 149건이었다.
     * 등급 체계는 공단 자료로 확인이 필요하다 (CLAUDE.md 용어집).
     */
    private static final Pattern JUDGMENT_GRADE = Pattern.compile("정상[AB](\\(경계\\))?|질환의심|유질환자?");

    public ValidationResult validate(CheckupReport report, String source) {
        return run(report, List.of(), report.items() == null ? List.of() : report.items(), source);
    }

    /** 재요청으로 받은 항목만 검사해 기존 결과에 붙인다. 이미 검사한 항목은 다시 검사하지 않는다. */
    public ValidationResult validateAdditional(CheckupReport current, List<ReportItem> additional, String source) {
        return run(current, current.items(), additional == null ? List.of() : additional, source);
    }

    private ValidationResult run(CheckupReport report, List<ReportItem> alreadyKept, List<ReportItem> candidates,
                                 String source) {
        List<String> lines = SourceText.lines(source);
        List<Issue> issues = new ArrayList<>();
        List<ReportItem> kept = new ArrayList<>(alreadyKept);
        Set<String> seen = new HashSet<>();
        Set<ItemCode> keptCodes = new HashSet<>();
        Map<String, String> otherValues = new HashMap<>();  // OTHER 항목명 → 값
        for (ReportItem item : alreadyKept) {
            seen.add(item.code() + "|" + SourceText.squash(item.value()));
            if (item.code() != ItemCode.OTHER) {
                keptCodes.add(item.code());
            } else {
                otherValues.put(otherName(item), SourceText.squash(item.value()));
            }
        }

        for (ReportItem item : candidates) {
            if (item.code() == null || item.value() == null || item.value().isBlank()) {
                issues.add(new Issue(IssueType.EMPTY, item.code(), "code 또는 value 없음"));
                continue;
            }
            String key = item.code() + "|" + SourceText.squash(item.value());
            if (item.code() != ItemCode.OTHER && !seen.add(key)) {
                issues.add(new Issue(IssueType.DUPLICATE, item.code(), item.value()));
                continue;
            }
            if (!SourceText.containsValue(source, item.value())) {
                issues.add(new Issue(IssueType.UNGROUNDED_VALUE, item.code(), item.value()));
                continue;
            }
            if (item.code() == ItemCode.OTHER && !nameAndValueOnSameLine(item, lines)) {
                // 표준 코드 표에 없는 항목은 모델이 항목명을 원문에서 그대로 옮겨야 한다.
                // 2026-11-09 v2 평가: 줄바꿈 조각 "참조) 정상"에 다른 항목의 값(3.88)을 붙인 가짜 항목이 나왔다.
                issues.add(new Issue(IssueType.OTHER_NAME_VALUE_APART, item.code(), item.rawName() + "=" + item.value()));
                continue;
            }
            String implausible = implausible(item);
            if (implausible != null) {
                issues.add(new Issue(IssueType.IMPLAUSIBLE, item.code(), implausible));
                continue;
            }
            if (item.code() != ItemCode.OTHER && !keptCodes.add(item.code())) {
                issues.add(new Issue(IssueType.CONFLICT, item.code(), "같은 코드에 다른 값: " + item.value()));
                continue;
            }
            if (item.code() == ItemCode.OTHER) {
                // OTHER는 코드로 구분되지 않으므로 항목명으로 같은 항목인지 본다.
                // 2026-11-09 sum-003: 재요청이 "T-Bil 0.8 0.7 mg/dL 0.2-1.2" 한 줄에서 금회·전회·참고치를 각각 항목으로 뽑았다.
                String previous = otherValues.putIfAbsent(otherName(item), SourceText.squash(item.value()));
                if (previous != null) {
                    issues.add(previous.equals(SourceText.squash(item.value()))
                            ? new Issue(IssueType.DUPLICATE, item.code(), item.rawName() + "=" + item.value())
                            : new Issue(IssueType.CONFLICT, item.code(),
                                    "같은 항목명에 다른 값: " + item.rawName() + "=" + item.value()));
                    continue;
                }
            }
            kept.add(groundFields(item, lines, issues));
        }

        CheckupReport cleaned = new CheckupReport(report.examDate(), report.examType(), report.sex(), report.age(), kept);
        return new ValidationResult(cleaned, issues, uncoveredSegments(lines, kept, report.age()));
    }

    private static boolean nameAndValueOnSameLine(ReportItem item, List<String> lines) {
        if (item.rawName() == null || item.rawName().isBlank()) {
            return false;
        }
        return SourceText.valueWindows(lines, item.value()).stream()
                .anyMatch(window -> SourceText.containsIgnoringSpaces(window, item.rawName()));
    }

    private static String otherName(ReportItem item) {
        return SourceText.squash(item.rawName() == null ? "" : item.rawName());
    }

    private static String implausible(ReportItem item) {
        if (item.code().kind() != ItemCode.ValueKind.NUMERIC) {
            return null;
        }
        if (!SourceText.isNumber(item.value())) {
            return "숫자가 아님: " + item.value();
        }
        double[] range = PLAUSIBLE.get(item.code());
        double value = Double.parseDouble(item.value().strip());
        if (range != null && (value < range[0] || value > range[1])) {
            return "가능 범위 밖: " + item.value();
        }
        return null;
    }

    /** 값이 들어 있는 줄에서 단위·참고치·판정을 찾는다. 없으면 null로 바꾼다 (지어낸 필드 제거). */
    private static ReportItem groundFields(ReportItem item, List<String> lines, List<Issue> issues) {
        // 줄바꿈된 값이어도 두 줄을 함께 보지 않는다. 둘째 줄에는 다른 열(예: 전회 결과 "정상")이 섞여 있어
        // 지어낸 판정이 통과한다. 2026-11-09 재생 비교: 함께 보면 std 판정 3건을 살리지만 sum 필드 8건이 새로 틀렸다.
        // 틀린 판정을 보여 주는 것이 비워 두는 것보다 위험하므로 보수적으로 둔다.
        List<String> valueLines = lines.stream().filter(line -> SourceText.containsValue(line, item.value())).toList();
        if (!SourceText.containsIgnoringSpaces(String.join("\n", lines), item.rawName() == null ? "" : item.rawName())) {
            issues.add(new Issue(IssueType.RAW_NAME_NOT_IN_SOURCE, item.code(), item.rawName()));
        }
        String unit = keepIfOnLine(item, "unit", item.unit(), valueLines, issues);
        String reference = keepIfOnLine(item, "referenceRaw", notJudgmentGrade(item, issues), valueLines, issues);
        String judgment = keepIfOnLine(item, "centerJudgment", item.centerJudgment(), valueLines, issues);
        return new ReportItem(item.code(), item.rawName(), item.value().strip(), unit, reference, judgment);
    }

    private static String notJudgmentGrade(ReportItem item, List<Issue> issues) {
        String reference = item.referenceRaw();
        if (reference != null && JUDGMENT_GRADE.matcher(SourceText.squash(reference)).matches()) {
            issues.add(new Issue(IssueType.MISPLACED_FIELD, item.code(), "referenceRaw=" + reference + " (판정 등급)"));
            return null;
        }
        return reference;
    }

    private static String keepIfOnLine(ReportItem item, String field, String fieldValue, List<String> valueLines,
                                       List<Issue> issues) {
        if (fieldValue == null || fieldValue.isBlank() || EMPTY_MARK.matcher(fieldValue.strip()).matches()) {
            return null;  // "-", "–" 같은 '없음' 표기는 값이 없는 것으로 본다
        }
        boolean onLine = valueLines.stream().anyMatch(line -> SourceText.containsIgnoringSpaces(line, fieldValue));
        if (!onLine) {
            issues.add(new Issue(IssueType.UNGROUNDED_FIELD, item.code(), field + "=" + fieldValue));
            return null;
        }
        return fieldValue;
    }

    /**
     * 검사 결과처럼 보이는데 추출된 값이 하나도 없는 조각(쉼표로 나눈 줄 조각).
     * 검사 결과처럼 보인다 = 날짜·연도·나이가 아닌 숫자가 있거나, 음성/양성/정상 같은 결과어가 있다.
     * v1은 줄 단위로 봐서 "시력 좌 0.7, ..., 청력 좌 정상"에서 청력만 뽑혀도 시력 누락을 놓쳤다.
     */
    private static List<String> uncoveredSegments(List<String> lines, List<ReportItem> kept, Integer age) {
        List<String> uncovered = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (META_LINE.matcher(line).find()) {
                continue;
            }
            List<String> segments = SourceText.segments(line);
            for (int j = 0; j < segments.size(); j++) {
                String segment = segments.get(j);
                if (!looksLikeResult(segment, age)) {
                    continue;
                }
                // 줄바꿈으로 나뉜 값: 윗줄에서 이어진 값은 첫 조각을, 아랫줄로 이어지는 값은 마지막 조각을 덮는다
                String previous = j == 0 && i > 0 ? lines.get(i - 1) : null;
                String next = j == segments.size() - 1 && i + 1 < lines.size() ? lines.get(i + 1) : null;
                boolean covered = kept.stream().anyMatch(item -> SourceText.containsValue(segment, item.value())
                        || previous != null && SourceText.spansLines(previous, line, item.value())
                        || next != null && SourceText.spansLines(line, next, item.value()));
                if (!covered) {
                    uncovered.add(segment);
                }
            }
        }
        return uncovered;
    }

    private static boolean looksLikeResult(String line, Integer age) {
        String withoutDates = DATE.matcher(line).replaceAll(" ");
        var numbers = NUMBER.matcher(withoutDates);
        while (numbers.find()) {
            String number = numbers.group();
            boolean yearLike = number.matches("(19|20)\\d{2}");
            boolean isAge = age != null && number.equals(String.valueOf(age));
            if (!yearLike && !isAge) {
                return true;
            }
        }
        return RESULT_WORD.matcher(line).find();
    }

    public enum IssueType {
        EMPTY, DUPLICATE, UNGROUNDED_VALUE, OTHER_NAME_VALUE_APART, IMPLAUSIBLE, CONFLICT, UNGROUNDED_FIELD,
        MISPLACED_FIELD, RAW_NAME_NOT_IN_SOURCE,
        /** 출력이 상한에서 잘려 JSON이 깨졌고, 완성된 항목까지만 살렸다 ({@link ReportExtractor}가 기록) */
        TRUNCATED_OUTPUT
    }

    public record Issue(IssueType type, ItemCode code, String detail) {
    }

    /**
     * @param report 고친 결과 (버린 항목 제외, 근거 없는 필드는 null)
     * @param issues 발견한 문제
     * @param uncoveredSegments 누락이 의심되는 원문 조각 (쉼표로 나눈 줄 조각)
     */
    public record ValidationResult(CheckupReport report, List<Issue> issues, List<String> uncoveredSegments) {
    }
}
