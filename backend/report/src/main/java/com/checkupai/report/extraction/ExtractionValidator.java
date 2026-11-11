package com.checkupai.report.extraction;

import java.time.LocalDate;
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
 * 추출 결과를 원문과 대조해 고치고, 빠진 것으로 보이는 줄을 찾는다 (ADR 0004, ADR 0003 4절의 구체화).
 *
 * <p>LLM을 부르지 않는 결정적(deterministic) 검사만 한다. 재요청 여부는 {@link ReportExtractor}가 정한다.
 * <ol>
 *   <li>중복 제거: 같은 코드·같은 값이 여러 번 나오면 하나만 남긴다 (기준선에서 관찰된 반복 오류)</li>
 *   <li>값 원문 대조: 원문에 없는 값은 버린다 (지어낸 값 차단)</li>
 *   <li>항목 모양: 숫자 여러 개가 뭉친 값, 값이 들어 있는 OTHER 항목명은 버린다 (줄 전체를 옮긴 것)</li>
 *   <li>OTHER 항목: 항목명과 값이 같은 줄에 없으면 버린다 (다른 항목 값을 빌려 만든 가짜 항목 차단)</li>
 *   <li>숫자 항목 상식 검사: 숫자로 읽히지 않는 값은 버리고(부등호 값은 남긴다), 생리적으로 불가능한 값은 확인 필요로 넘긴다</li>
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
    /**
     * 결과가 아닌 숫자: 전화번호(02-123-4567)와 영문 식별자(SYN-50520). 숫자 검사 전에 지운다.
     * 2026-11-09 heldout-v1: "문의 000-0000-0000", "등록번호: SYN-50520" 줄을 결과로 보고 매번 재요청했다.
     */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)\\d{2,4}-\\d{3,4}-\\d{4}(?!\\d)");
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![A-Za-z])[A-Za-z]{2,}-\\d[\\d-]*");
    private static final Pattern RESULT_WORD = Pattern.compile("음성|양성|정상|비정상");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** 숫자만 있는 값 조각. 괄호로 감싼 숫자("1+ (30)"의 "(30)")도 하나로 센다. "1+"처럼 기호가 붙은 결과는 세지 않는다. */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[(\\[]?[<>≤≥]?\\d+(\\.\\d+)?[)\\]]?");
    /** 숫자 사이의 범위·분수 기호와 그 양옆 공백 ("0 - 2", "120 / 80") */
    private static final Pattern NUMBER_CONNECTOR = Pattern.compile("(\\d)\\s*([-~/])\\s*(\\d)");
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
        List<ReviewItem> needsReview = new ArrayList<>();
        List<ReportItem> kept = new ArrayList<>(alreadyKept);
        Set<String> seen = new HashSet<>();
        Set<ItemCode> keptCodes = new HashSet<>();
        Map<String, String> otherValues = new HashMap<>();  // OTHER 항목명 → 값
        Map<ItemCode, ReportItem> inequalityKept = new EnumMap<>(ItemCode.class);  // 읽히는 값에 밀리는 부등호 값
        for (ReportItem item : alreadyKept) {
            seen.add(item.code() + "|" + SourceText.squash(item.value()));
            if (item.code() != ItemCode.OTHER) {
                keptCodes.add(item.code());
                if (isInequality(item)) {
                    inequalityKept.put(item.code(), item);
                }
            } else {
                otherValues.put(otherName(item), SourceText.squash(item.value()));
            }
        }

        // 부등호 값(">90")은 같은 코드의 읽히는 값보다 뒤에 검사한다. 먼저 오면 읽히는 값을 충돌로 밀어낸다.
        // 2026-11-11 Fable 리뷰: 참고치를 옮긴 "<120"이 먼저 오자 실제 결과 118이 CONFLICT로 버려졌다
        List<ReportItem> ordered = new ArrayList<>(candidates.stream().filter(item -> !isInequality(item)).toList());
        ordered.addAll(candidates.stream().filter(ExtractionValidator::isInequality).toList());
        for (ReportItem item : ordered) {
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
            // 아래 두 검사는 결과지 양식과 무관한 "항목 하나"의 모양이다. 원문 대조를 통과해도 항목이 아니다.
            // 2026-11-09 heldout-v2(처음 보는 5번째 양식): 재요청이 들여온 OTHER 항목 21개가 모두 쓸모없는 항목이었다.
            // 줄 전체를 항목명으로 옮기면("시력: 좌 0.5 우 0.9", "상담 문의 1588-0000 ...") 항목명과 값이 늘 같은 줄에 있어
            // OTHER 검사를 통과했고, 3개년 추이표 한 줄의 값을 통째로 옮긴 것("3.8 4.1 4.8 3.5~5.2")도 원문에 있었다.
            if (bundlesValues(item.value())) {
                // 여러 해·여러 열의 값이 뭉쳤다. 어느 것이 이번 결과인지 코드가 정할 수 없으므로 버린다.
                issues.add(new Issue(IssueType.BUNDLED_VALUES, item.code(), item.rawName() + "=" + item.value()));
                continue;
            }
            if (item.code() == ItemCode.OTHER && nameHoldsValue(item)) {
                // OTHER는 항목명이 곧 항목의 정체다. 항목명 안에 값이 들어 있으면 이름이 아니라 줄을 옮긴 것이다.
                // 표준 코드 항목은 코드가 정체이므로 항목명이 줄 전체여도 버리지 않는다.
                issues.add(new Issue(IssueType.OTHER_NAME_HOLDS_VALUE, item.code(), item.rawName() + "=" + item.value()));
                continue;
            }
            if (item.code() == ItemCode.OTHER && !nameAndValueOnSameLine(item, lines, report.age())) {
                // 표준 코드 표에 없는 항목은 모델이 항목명을 원문에서 그대로 옮겨야 한다.
                // 2026-11-09 v2 평가: 줄바꿈 조각 "참조) 정상"에 다른 항목의 값(3.88)을 붙인 가짜 항목이 나왔다.
                issues.add(new Issue(IssueType.OTHER_NAME_VALUE_APART, item.code(), item.rawName() + "=" + item.value()));
                continue;
            }
            String implausible = implausible(item);
            if (implausible != null) {
                issues.add(new Issue(IssueType.IMPLAUSIBLE, item.code(), implausible));
                if (isOutOfRange(item)) {
                    // 원문에 있는 범위 밖 수치는 버리지 않고 "확인 필요"로 남긴다 (ADR 0007 1절, 가드레일 원칙 3).
                    // 읽히지 않는 값("정상", "150/95")은 숫자 항목의 결과가 아니므로 남기지 않는다
                    needsReview.add(new ReviewItem(item.code(), item.rawName(), item.value(), implausible));
                }
                continue;
            }
            ReportItem weaker = isInequality(item) ? null : inequalityKept.remove(item.code());
            if (weaker != null) {
                // 이미 남긴 부등호 값보다 읽히는 값을 남긴다 (재요청이 실제 결과를 가져온 경우)
                kept.remove(weaker);
                keptCodes.remove(item.code());
                issues.add(new Issue(IssueType.CONFLICT, item.code(),
                        "부등호 값 " + weaker.value() + " 대신 읽히는 값을 남김: " + item.value()));
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
            ReportItem grounded = groundFields(item, lines, issues);
            kept.add(grounded);
            if (isInequality(item)) {
                inequalityKept.put(item.code(), grounded);
            }
        }

        CheckupReport cleaned = new CheckupReport(report.examDate(), report.examType(), report.sex(), report.age(), kept);
        return new ValidationResult(cleaned, issues, uncoveredSegments(lines, kept, report.age(), report.examDate()),
                List.copyOf(needsReview));
    }

    /**
     * 항목명과 값이 같은 줄(또는 줄바꿈으로 이어진 범위)에 있는가.
     * 항목명이 PDF 표 칸에서 줄바꿈되면 이름 앞부분만 윗줄에 남는다(2026-11-09 heldout-v1: "고감도 C반응단백" /
     * "(hs-CRP) 0.8 ..."). 그래서 윗줄이 결과 없는 이름 조각이면 이어서 본다. 결과어가 있는 윗줄("참조) 정상")은 잇지 않는다.
     */
    private static boolean nameAndValueOnSameLine(ReportItem item, List<String> lines, Integer age) {
        if (item.rawName() == null || item.rawName().isBlank()) {
            return false;
        }
        for (int i = 0; i < lines.size(); i++) {
            String window = SourceText.valueWindowAt(lines, i, item.value());
            if (window != null && nameNear(window, i, item.rawName(), lines, age)) {
                return true;
            }
        }
        return false;
    }

    /** text(i번째 줄에서 시작)에 항목명이 있거나, 윗줄이 이름 조각이고 이어 보면 항목명이 있다 */
    private static boolean nameNear(String text, int line, String rawName, List<String> lines, Integer age) {
        if (rawName == null || rawName.isBlank()) {
            return false;
        }
        if (SourceText.containsIgnoringSpaces(text, rawName)) {
            return true;
        }
        if (line == 0) {
            return false;
        }
        String previous = lines.get(line - 1);
        return isNameFragment(previous, age) && SourceText.containsIgnoringSpaces(previous + " " + text, rawName);
    }

    private static boolean isNameFragment(String line, Integer age) {
        return !looksLikeResult(line, age) && !SourceText.isHeaderLine(line);
    }

    /**
     * 공백으로 나눈 값 조각 중 숫자만 있는 조각이 2개 이상이다 ("4.6 4.4 4.3", "- 1.3 1.1 0.2~1.2").
     * 범위·분수 기호(-, ~, /) 양옆의 숫자는 한 값으로 본다. 2026-11-10 Fable 리뷰: 요침사 "0 - 2", 혈압 "120 / 80"처럼
     * PDF에서 기호 양옆에 공백이 끼면 숫자 2개로 세어 맞는 값을 버렸다.
     */
    private static boolean bundlesValues(String value) {
        String joined = NUMBER_CONNECTOR.matcher(value.strip()).replaceAll("$1$2$3");
        return SPACES.splitAsStream(joined).filter(token -> PLAIN_NUMBER.matcher(token).matches()).count() >= 2;
    }

    /**
     * 항목명 안에 값이 독립된 낱말로 들어 있다. 앞뒤가 글자·숫자·점·하이픈이면 낱말의 일부로 보고 인정하지 않는다
     * ("HbA1c"의 1, "25-OH 비타민D"의 25는 값이 아니라 이름의 일부다).
     */
    private static boolean nameHoldsValue(ReportItem item) {
        if (item.rawName() == null || item.rawName().isBlank()) {
            return false;
        }
        String value = SourceText.collapse(item.value());
        return Pattern.compile("(?<![\\p{L}\\p{N}.-])" + Pattern.quote(value) + "(?![\\p{L}\\p{N}.-])")
                .matcher(SourceText.collapse(item.rawName()))
                .find();
    }

    private static String otherName(ReportItem item) {
        return SourceText.squash(item.rawName() == null ? "" : item.rawName());
    }

    /** 숫자로 읽히지만 생리적으로 가능한 범위 밖이다 (implausible의 두 경우 중 하나) */
    private static boolean isOutOfRange(ReportItem item) {
        return item.code().kind() == ItemCode.ValueKind.NUMERIC && NumericValue.parse(item.value()).isPresent()
                && PLAUSIBLE.containsKey(item.code());
    }

    /** 숫자 항목의 부등호 값(">90"): 남기지만 판정하지 않고, 같은 코드의 읽히는 값에 밀린다 */
    private static boolean isInequality(ReportItem item) {
        return item.code() != null && item.code().kind() == ItemCode.ValueKind.NUMERIC
                && NumericValue.shape(item.value()) == NumericValue.Shape.INEQUALITY;
    }

    /**
     * 숫자 항목의 값이 결과로 읽히지 않으면 이유를 돌려준다.
     *
     * <p>두 모양만 원문 표기 그대로 남긴다 (ADR 0004 보완, 2026-11-11, {@link NumericValue.Shape}).
     * <ul>
     *   <li>표시·쉼표를 떼면 숫자 하나("64 L", "9.3▼", "1,120"): 판정 코드가 같은 규칙으로 떼어 판정한다.
     *       가능 범위 검사는 뗀 숫자로 한다.</li>
     *   <li>부등호 + 숫자 하나(">90"): 판정 코드가 "확인 필요"로 알린다. 참고치를 옮긴 "<120"도 남는 대가가 있어,
     *       읽히는 값에 밀리고 결과 줄을 차지하지 않게 했다.</li>
     * </ul>
     * 그 밖("150/95", "13.0~16.5", "650kg")은 예전처럼 버린다. 처음에는 숫자만 들어 있으면 남겼는데, 2026-11-11 Fable 리뷰에서
     * 이런 값이 맞는 값을 밀어내고 가능 범위 검사도 건너뛰는 것이 재현됐다.
     * 예전에는 위 두 모양도 "숫자가 아님"으로 버려, 판정 코드가 읽을 수 있는 값이 판정까지 오지 못했다
     * (표기 변형 평가에서 조용히 지나간 4건).
     */
    private static String implausible(ReportItem item) {
        if (item.code().kind() != ItemCode.ValueKind.NUMERIC) {
            return null;
        }
        switch (NumericValue.shape(item.value())) {
            case OTHER:
                return "숫자가 아님: " + item.value();
            case INEQUALITY:
                return null;
            default:
                break;
        }
        double[] range = PLAUSIBLE.get(item.code());
        double value = NumericValue.parse(item.value()).getAsDouble();
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
     * 검사 결과처럼 보이는데 아무 항목도 차지하지 않은 조각(쉼표로 나눈 줄 조각). 재요청 대상이다.
     *
     * <ul>
     *   <li>검사 결과처럼 보인다 = 날짜·연도·나이·전화번호·식별자가 아닌 숫자가 있거나, 음성/양성/정상 같은 결과어가 있다.</li>
     *   <li>수검자 정보 줄(단어 목록 또는 이미 뽑은 검진일이 있는 줄)은 보지 않는다.</li>
     *   <li>항목은 항목명과 값이 함께 있는 조각을 모두 차지한다. 항목명이 원문에 없으면 값이 있는 조각 중
     *       아직 아무도 차지하지 않은 것 하나를 차지한다. 값만 같고 항목명이 없는 다른 조각은 덮지 않는다.</li>
     * </ul>
     * v2.2까지는 값만 같으면 모든 조각을 덮은 것으로 봤다. 2026-11-09 heldout-v1에서 흉부 X-ray의 "정상"이
     * 청력 줄 "정상/정상"을, HDL 74가 "DBP 74" 줄을 덮어 누락을 다시 묻지 못했다(10건).
     */
    private static List<String> uncoveredSegments(List<String> lines, List<ReportItem> kept, Integer age,
                                                  LocalDate examDate) {
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (isMetaLine(lines.get(i), examDate)) {
                continue;
            }
            List<String> parts = SourceText.segments(lines.get(i));
            for (int j = 0; j < parts.size(); j++) {
                segments.add(new Segment(i, j, j == 0, j == parts.size() - 1, parts.get(j)));
            }
        }

        Set<Segment> claimed = new HashSet<>();
        List<ReportItem> unnamed = new ArrayList<>();
        for (ReportItem item : kept) {
            if (isInequality(item)) {
                // 부등호 값은 결과 줄을 차지하지 않는다. 참고치("<120")를 값으로 잘못 옮겼으면 실제 결과가 그 줄에 남아 있다.
                // 2026-11-11 Fable 리뷰: 차지하게 두면 그 줄이 누락 의심에서 빠져 실제 값을 다시 물을 길이 없었다
                continue;
            }
            List<Segment> candidates = segments.stream().filter(s -> holdsValue(s, item.value(), lines)).toList();
            List<Segment> named = candidates.stream()
                    .filter(s -> nameNear(s.text(), s.line(), item.rawName(), lines, age)).toList();
            if (!named.isEmpty()) {
                // 항목명과 값이 함께 있는 조각은 모두 이 항목의 것이다 (예: 결과 줄과, 같은 값을 다시 말하는 소견 문장)
                named.forEach(s -> claim(s, item.value(), segments, lines, claimed));
            } else if (!candidates.isEmpty()) {
                unnamed.add(item);
            }
        }
        for (ReportItem item : unnamed) {
            List<Segment> candidates = segments.stream().filter(s -> holdsValue(s, item.value(), lines)).toList();
            Segment target = candidates.stream().filter(s -> !claimed.contains(s)).findFirst()
                    .orElse(candidates.getFirst());
            claim(target, item.value(), segments, lines, claimed);
        }

        return segments.stream()
                .filter(s -> !claimed.contains(s) && looksLikeResult(s.text(), age))
                .map(Segment::text)
                .toList();
    }

    /** 조각을 차지한다. 값이 줄바꿈으로 이웃 줄에 걸쳐 있으면 이웃 조각도 함께 차지한다 */
    private static void claim(Segment segment, String value, List<Segment> segments, List<String> lines,
                              Set<Segment> claimed) {
        claimed.add(segment);
        if (SourceText.containsValue(segment.text(), value)) {
            return;
        }
        int line = segment.line();
        segments.stream()
                .filter(s -> segment.first() && s.line() == line - 1 && s.last()
                        && SourceText.spansLines(lines.get(line - 1), lines.get(line), value)
                        || segment.last() && s.line() == line + 1 && s.first()
                        && SourceText.spansLines(lines.get(line), lines.get(line + 1), value))
                .forEach(claimed::add);
    }

    /** 조각에 값이 있다. 줄바꿈으로 나뉜 값은 윗줄 끝 조각·아랫줄 첫 조각과 이어서 본다 */
    private static boolean holdsValue(Segment segment, String value, List<String> lines) {
        int line = segment.line();
        return SourceText.containsValue(segment.text(), value)
                || segment.first() && line > 0 && SourceText.spansLines(lines.get(line - 1), lines.get(line), value)
                || segment.last() && line + 1 < lines.size()
                && SourceText.spansLines(lines.get(line), lines.get(line + 1), value);
    }

    /** 수검자 정보 줄: 정해 둔 단어가 있거나, 이미 뽑은 검진일이 들어 있다(형식은 2026-09-16, 2026/09/16, 2026. 9. 16. 등) */
    private static boolean isMetaLine(String line, LocalDate examDate) {
        if (META_LINE.matcher(line).find()) {
            return true;
        }
        if (examDate == null) {
            return false;
        }
        return Pattern.compile(examDate.getYear() + "\\s*[-./년]\\s*0?" + examDate.getMonthValue()
                + "\\s*[-./월]\\s*0?" + examDate.getDayOfMonth() + "(?!\\d)").matcher(line).find();
    }

    private record Segment(int line, int index, boolean first, boolean last, String text) {
    }

    private static boolean looksLikeResult(String line, Integer age) {
        String withoutDates = IDENTIFIER.matcher(PHONE.matcher(DATE.matcher(line).replaceAll(" ")).replaceAll(" "))
                .replaceAll(" ");
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
        EMPTY, DUPLICATE, UNGROUNDED_VALUE,
        /** 값에 숫자 여러 개가 뭉쳐 있다 (여러 해·여러 열을 통째로 옮김) */
        BUNDLED_VALUES,
        /** OTHER 항목명 안에 값이 들어 있다 (항목명이 아니라 줄 전체를 옮김) */
        OTHER_NAME_HOLDS_VALUE,
        OTHER_NAME_VALUE_APART, IMPLAUSIBLE, CONFLICT, UNGROUNDED_FIELD, MISPLACED_FIELD, RAW_NAME_NOT_IN_SOURCE,
        /** 출력이 상한에서 잘려 JSON이 깨졌고, 완성된 항목까지만 살렸다 ({@link ReportExtractor}가 기록) */
        TRUNCATED_OUTPUT,
        /** 1차 출력이 비정상적으로 적다: 누락 의심 조각이 뽑은 항목보다 많다 ({@link ReportExtractor}가 기록, 재요청으로 복구) */
        INCOMPLETE_FIRST_PASS
    }

    public record Issue(IssueType type, ItemCode code, String detail) {
    }

    /**
     * @param report 고친 결과 (버린 항목 제외, 근거 없는 필드는 null)
     * @param issues 발견한 문제
     * @param uncoveredSegments 누락이 의심되는 원문 조각 (쉼표로 나눈 줄 조각)
     * @param needsReview 원문에 있지만 가능 범위 밖이라 결과에 넣지 않은 항목 (확인 필요, ADR 0007)
     */
    public record ValidationResult(CheckupReport report, List<Issue> issues, List<String> uncoveredSegments,
                                   List<ReviewItem> needsReview) {
    }
}
