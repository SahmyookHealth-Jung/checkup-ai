package com.checkupai.report.explanation;

import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.NumericValue;
import com.checkupai.report.extraction.ReportItem;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 국가건강검진 검사항목별 판정기준으로 위험을 판정한다 (ADR 0007 4절). 판정은 LLM이 아니라 이 코드가 한다.
 *
 * <p>기준 수치는 코드에 적지 않고 출처가 붙은 데이터 파일({@value #RESOURCE})에 둔다 (CLAUDE.md 가드레일 원칙 6).
 * 출처: 건강검진 실시기준 [별표 4의 별첨] 검사항목별 판정기준, 보건복지부고시 제2026-6호.
 * 표의 구간을 어떻게 읽었는지는 데이터 파일의 interpretation에 적었다.
 *
 * <p>모르는 표기는 판정하지 않는다 (ADR 0007 보완, 2026-11-11). 단위가 대표 단위와 다르거나, 부등호 값이거나,
 * 숫자로 읽지 못하거나, 아는 글자 표기가 아니면 {@link Judgment#NOT_JUDGED}와 이유를 돌려준다.
 * 해설 서비스는 이런 항목을 "확인 필요"로 알린다. 틀린 판정이나 조용한 누락보다 "모른다"가 안전하다.
 * 단위 환산은 하지 않는다.
 *
 * <p>예외로, eGFR 단위가 1.73m² 보정 표기 없이 mL/min으로 적힌 경우는 두 해석(보정값을 줄여 쓴 것, 보정하지 않은
 * 절대값)을 모두 계산해 판정이 같을 때만 판정한다 (ADR 0007 보완, 2026-11-14). 성별을 모를 때와 같은 방식이다.
 */
@Component
public class ScreeningCriteria {

    static final String RESOURCE = "criteria/general-screening-2026-6.json";

    /** eGFR 보정의 기준 체표면적 (m²). 판정기준표의 단위 mL/min/1.73m²의 그 값이다 */
    private static final double STANDARD_BSA = 1.73;

    private final CriteriaFile file;
    private final String sourceLabel;

    public ScreeningCriteria() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.file = JsonMapper.builder().build().readValue(in, CriteriaFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("판정 기준 파일을 읽지 못했다: " + RESOURCE, e);
        }
        this.sourceLabel = file.source().title() + " (" + file.source().edition() + ")";
    }

    /** 단위를 모를 때의 판정. 단위 확인을 건너뛴다 */
    public CriteriaJudgment judge(ItemCode code, String value, CheckupReport.Sex sex) {
        return judge(code, value, null, sex);
    }

    /** 결과지의 한 항목을 판정한다. 성별과 체격(신장·체중)은 결과지에서 가져온다 */
    public CriteriaJudgment judge(ReportItem item, CheckupReport report) {
        return judge(item.code(), item.value(), item.unit(), report == null ? null : report.sex(),
                report == null ? OptionalDouble.empty() : bodySurfaceArea(report.items()));
    }

    /**
     * @param unit 결과지에 적힌 단위. 비어 있으면 대표 단위로 본다. 대표 단위와 다르면 판정하지 않는다
     */
    public CriteriaJudgment judge(ItemCode code, String value, String unit, CheckupReport.Sex sex) {
        return judge(code, value, unit, sex, OptionalDouble.empty());
    }

    /**
     * @param bodySurfaceArea 체표면적 (m²). eGFR 단위가 mL/min일 때만 쓴다. 없으면 그 경우를 판정하지 않는다
     */
    public CriteriaJudgment judge(ItemCode code, String value, String unit, CheckupReport.Sex sex,
                                  OptionalDouble bodySurfaceArea) {
        ItemCriteria criteria = file.items().get(code.name());
        if (criteria == null || value == null || value.isBlank()) {
            return new CriteriaJudgment(code, value, Judgment.NOT_JUDGED, null, null, null, null);
        }
        Outcome outcome;
        if (criteria.textBands() != null) {
            outcome = judgeText(criteria.textBands(), value.strip());
        } else if (code == ItemCode.EGFR && unindexedEgfrUnit(unit)) {
            outcome = judgeUnindexedEgfr(criteria, value.strip(), unit.strip(), sex, bodySurfaceArea);
        } else {
            outcome = judgeNumber(criteria, value.strip(), unit, code.canonicalUnit(), sex);
        }
        return new CriteriaJudgment(code, value, outcome.judgment(), criteria.row(), sourceLabel, outcome.note(),
                outcome.unitNote());
    }

    /**
     * 체표면적 (m²). 결과지의 신장(cm)과 체중(kg)으로 구한다. 둘 중 하나라도 없거나 단위가 다르면 비어 있다.
     * 공식: Du Bois & Du Bois (Arch Intern Med 1916;17:863), BSA = 0.007184 × 체중^0.425 × 신장^0.725.
     */
    static OptionalDouble bodySurfaceArea(List<ReportItem> items) {
        OptionalDouble height = measurement(items, ItemCode.HEIGHT);
        OptionalDouble weight = measurement(items, ItemCode.WEIGHT);
        if (height.isEmpty() || weight.isEmpty() || height.getAsDouble() <= 0 || weight.getAsDouble() <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(0.007184 * Math.pow(weight.getAsDouble(), 0.425) * Math.pow(height.getAsDouble(), 0.725));
    }

    private static OptionalDouble measurement(List<ReportItem> items, ItemCode code) {
        return items == null ? OptionalDouble.empty() : items.stream()
                .filter(item -> item.code() == code && item.value() != null
                        && sameUnit(item.unit(), code.canonicalUnit()) && !NumericValue.hasInequality(item.value()))
                .map(item -> NumericValue.parse(item.value()))
                .filter(OptionalDouble::isPresent)
                .findFirst().orElse(OptionalDouble.empty());
    }

    /** eGFR 단위가 1.73m² 보정 표기 없이 mL/min으로만 적혔는가 */
    private static boolean unindexedEgfrUnit(String unit) {
        return unit != null && normalizeUnit(unit).equals("ml/min");
    }

    /**
     * eGFR이 mL/min으로 적혔을 때. 검사실 eGFR은 보통 1.73m² 보정값이고(KDIGO 2012 CKD 가이드라인의 검사실 보고 권고)
     * mL/min은 그것을 줄여 쓴 것일 수 있다. 하지만 보정하지 않은 절대값(= 보정값 × 체표면적 ÷ 1.73, 약 용량 계산에 쓴다)도
     * 같은 단위다. 어느 쪽인지 결과지로는 알 수 없으므로 두 해석을 모두 판정해, 같으면 그 판정을 쓰고 다르면 판정하지 않는다.
     * 2026-11-14 Codex 리뷰: 줄여 쓴 것으로만 보면 체격이 큰 사람의 절대값이 기준 60 근처에서 정상A로 잘못 나온다.
     */
    private static Outcome judgeUnindexedEgfr(ItemCriteria criteria, String value, String unit, CheckupReport.Sex sex,
                                              OptionalDouble bodySurfaceArea) {
        Outcome asIndexed = judgeNumber(criteria, value, null, null, sex);
        if (asIndexed.judgment() == Judgment.NOT_JUDGED) {
            return asIndexed;
        }
        if (bodySurfaceArea.isEmpty()) {
            return Outcome.unknown("단위에 1.73m² 보정 표기가 없음: " + unit + " (신장·체중이 없어 보정 여부의 영향을 확인하지 못함)");
        }
        double indexed = NumericValue.parse(value).getAsDouble() * STANDARD_BSA / bodySurfaceArea.getAsDouble();
        Outcome asAbsolute = judgeBands(criteria.bands(), indexed);
        if (asAbsolute.judgment() != asIndexed.judgment()) {
            return Outcome.unknown("단위에 1.73m² 보정 표기가 없음: " + unit + " (보정값인지에 따라 판정이 달라짐)");
        }
        return new Outcome(asIndexed.judgment(), null, "결과지 단위가 " + unit + "입니다. 1.73m² 보정값이든 보정하지 않은 값이든 "
                + "판정이 같아 판정했습니다. 신장·체중으로 계산한 체표면적을 썼습니다.");
    }

    /** 판정 기준의 출처 (문서명과 고시 번호) */
    public String sourceLabel() {
        return sourceLabel;
    }

    /** 판정과, 판정하지 못했을 때의 이유 */
    private record Outcome(Judgment judgment, String note, String unitNote) {

        static Outcome of(Judgment judgment) {
            return new Outcome(judgment, null, null);
        }

        static Outcome unknown(String note) {
            return new Outcome(Judgment.NOT_JUDGED, note, null);
        }
    }

    /** 글자 값은 소문자로 바꿔 기준 파일의 표기 목록과 맞춰 본다. 아는 표기가 아니면 판정하지 않는다 */
    private static Outcome judgeText(List<TextBand> bands, String value) {
        String text = value.toLowerCase(Locale.ROOT);
        return bands.stream()
                .filter(band -> Pattern.compile(band.pattern()).matcher(text).find())
                .map(band -> Outcome.of(band.judgment()))
                .findFirst()
                .orElse(Outcome.unknown("알 수 없는 표기: " + value));
    }

    private static Outcome judgeNumber(ItemCriteria criteria, String value, String unit, String canonicalUnit,
                                       CheckupReport.Sex sex) {
        if (!sameUnit(unit, canonicalUnit)) {
            return Outcome.unknown("단위가 다름: " + unit.strip() + " (기준 단위 " + canonicalUnit + ")");
        }
        if (NumericValue.hasInequality(value)) {
            return Outcome.unknown("실제 수치가 아닌 값: " + value);
        }
        OptionalDouble parsed = NumericValue.parse(value);
        if (parsed.isEmpty()) {
            return Outcome.unknown("숫자로 읽지 못함: " + value);
        }
        double number = parsed.getAsDouble();
        if (criteria.bySex() == null) {
            return judgeBands(criteria.bands(), number);
        }
        List<Band> bands = sex == null ? null : criteria.bySex().get(sex.name());
        if (bands != null) {
            return judgeBands(bands, number);
        }
        // 성별 기준인데 성별을 모른다. 두 성별 기준의 판정이 같으면 그 판정을 쓰고, 다르면 판정하지 않는다.
        // 2026-11-10 해설 평가: 추출이 성별을 놓친 보고서에서 감마지티피 196(남녀 모두 질환의심)의 진료 권고가 빠졌다
        Outcome male = judgeBands(criteria.bySex().get("MALE"), number);
        Outcome female = judgeBands(criteria.bySex().get("FEMALE"), number);
        return male.judgment() == female.judgment() && male.judgment() != Judgment.NOT_JUDGED ? male
                : Outcome.unknown("성별을 알 수 없어 기준을 고르지 못함");
    }

    private static Outcome judgeBands(List<Band> bands, double number) {
        if (bands == null) {
            return Outcome.unknown("판정기준표에 없는 구간");
        }
        return bands.stream()
                .filter(band -> band.contains(number))
                .map(band -> Outcome.of(band.judgment()))
                .findFirst()
                .orElse(Outcome.unknown("판정기준표에 없는 구간"));
    }

    /** 단위가 비어 있으면 대표 단위로 본다. 표기 차이(대소문자, ㎎·㎗·㎡ 같은 합자, 공백)와 IU/L = U/L은 같은 단위다 */
    static boolean sameUnit(String unit, String canonicalUnit) {
        return unit == null || unit.isBlank() || canonicalUnit == null
                || normalizeUnit(unit).equals(normalizeUnit(canonicalUnit));
    }

    static String normalizeUnit(String unit) {
        String normalized = unit.toLowerCase(Locale.ROOT).replaceAll("\\s+", "")
                .replace("㎎", "mg").replace("㎗", "dl").replace("㎖", "ml").replace("㎏", "kg").replace("㎝", "cm")
                .replace("㎡", "m2").replace("²", "2").replace("µ", "μ");
        return normalized.equals("iu/l") ? "u/l" : normalized;
    }

    record CriteriaFile(Source source, String interpretation, Map<String, ItemCriteria> items) {
    }

    record Source(String id, String title, String edition, String url) {
    }

    /** bands(성별 무관), bySex(성별 기준), textBands(글자 값) 중 하나를 쓴다 */
    record ItemCriteria(String row, List<Band> bands, Map<String, List<Band>> bySex, List<TextBand> textBands) {
    }

    /** min은 이상, minExclusive는 초과, max는 미만, maxInclusive는 이하 */
    record Band(Judgment judgment, Double min, Double minExclusive, Double max, Double maxInclusive) {

        boolean contains(double value) {
            return (min == null || value >= min) && (minExclusive == null || value > minExclusive)
                    && (max == null || value < max) && (maxInclusive == null || value <= maxInclusive);
        }
    }

    record TextBand(Judgment judgment, String pattern) {
    }
}
