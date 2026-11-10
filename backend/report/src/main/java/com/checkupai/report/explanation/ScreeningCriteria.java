package com.checkupai.report.explanation;

import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ItemCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
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
 */
@Component
public class ScreeningCriteria {

    static final String RESOURCE = "criteria/general-screening-2026-6.json";
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");

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

    public CriteriaJudgment judge(ItemCode code, String value, CheckupReport.Sex sex) {
        ItemCriteria criteria = file.items().get(code.name());
        if (criteria == null || value == null || value.isBlank()) {
            return new CriteriaJudgment(code, value, Judgment.NOT_JUDGED, null, null);
        }
        Judgment judgment = criteria.textBands() != null
                ? judgeText(criteria.textBands(), value.strip())
                : judgeNumber(criteria, value.strip(), sex);
        return new CriteriaJudgment(code, value, judgment, criteria.row(), sourceLabel);
    }

    /** 판정 기준의 출처 (문서명과 고시 번호) */
    public String sourceLabel() {
        return sourceLabel;
    }

    private static Judgment judgeText(List<TextBand> bands, String value) {
        return bands.stream()
                .filter(band -> Pattern.compile(band.pattern()).matcher(value).find())
                .map(TextBand::judgment)
                .findFirst()
                .orElse(Judgment.NOT_JUDGED);
    }

    private static Judgment judgeNumber(ItemCriteria criteria, String value, CheckupReport.Sex sex) {
        if (!NUMBER.matcher(value).matches()) {
            return Judgment.NOT_JUDGED;
        }
        List<Band> bands = criteria.bySex() == null ? criteria.bands()
                : sex == null ? null : criteria.bySex().get(sex.name());
        if (bands == null) {
            return Judgment.NOT_JUDGED;  // 성별 기준인데 성별을 모른다
        }
        double number = Double.parseDouble(value);
        return bands.stream()
                .filter(band -> band.contains(number))
                .map(Band::judgment)
                .findFirst()
                .orElse(Judgment.NOT_JUDGED);
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
