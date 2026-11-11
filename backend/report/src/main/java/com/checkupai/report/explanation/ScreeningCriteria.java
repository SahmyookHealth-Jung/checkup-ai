package com.checkupai.report.explanation;

import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ItemCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 */
@Component
public class ScreeningCriteria {

    static final String RESOURCE = "criteria/general-screening-2026-6.json";
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
    /** 값 앞뒤의 높음·낮음 표시: "140 H", "H 140", "140▲", "9.3 (L)", "140*" */
    private static final Pattern FLAG = Pattern.compile(
            "^[\\s(\\[]*(?:high|low|[hl]|[▲▼↑↓*])[\\s)\\]]*(?=[-+]?\\d)|(?<=\\d)[\\s(\\[]*(?:high|low|[hl]|[▲▼↑↓*])[\\s)\\]]*$",
            Pattern.CASE_INSENSITIVE);
    /** 천 단위 쉼표: "1,120" */
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");
    /** 실제 수치가 아닌 값: ">90", "<10", "400 이상" */
    private static final Pattern INEQUALITY = Pattern.compile("[<>≤≥≦≧]|이상|이하|미만|초과");

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

    /**
     * @param unit 결과지에 적힌 단위. 비어 있으면 대표 단위로 본다. 대표 단위와 다르면 판정하지 않는다
     */
    public CriteriaJudgment judge(ItemCode code, String value, String unit, CheckupReport.Sex sex) {
        ItemCriteria criteria = file.items().get(code.name());
        if (criteria == null || value == null || value.isBlank()) {
            return new CriteriaJudgment(code, value, Judgment.NOT_JUDGED, null, null, null);
        }
        Outcome outcome = criteria.textBands() != null
                ? judgeText(criteria.textBands(), value.strip())
                : judgeNumber(criteria, value.strip(), unit, code.canonicalUnit(), sex);
        return new CriteriaJudgment(code, value, outcome.judgment(), criteria.row(), sourceLabel, outcome.note());
    }

    /** 판정 기준의 출처 (문서명과 고시 번호) */
    public String sourceLabel() {
        return sourceLabel;
    }

    /** 판정과, 판정하지 못했을 때의 이유 */
    private record Outcome(Judgment judgment, String note) {

        static Outcome of(Judgment judgment) {
            return new Outcome(judgment, null);
        }

        static Outcome unknown(String note) {
            return new Outcome(Judgment.NOT_JUDGED, note);
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
        if (INEQUALITY.matcher(value).find()) {
            return Outcome.unknown("실제 수치가 아닌 값: " + value);
        }
        String cleaned = cleanNumber(value);
        if (!NUMBER.matcher(cleaned).matches()) {
            return Outcome.unknown("숫자로 읽지 못함: " + value);
        }
        double number = Double.parseDouble(cleaned);
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

    /** 높음·낮음 표시와 천 단위 쉼표를 뗀다 ("140 H" → "140", "1,120" → "1120") */
    static String cleanNumber(String value) {
        return THOUSANDS.matcher(FLAG.matcher(value.strip()).replaceAll("")).replaceAll("").strip();
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
