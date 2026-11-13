package com.checkupai.report.followup;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkupai.report.explanation.ScreeningCriteria;
import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.ReportItem;
import com.checkupai.report.followup.FollowupContextBuilder.ContextUnit;
import com.checkupai.report.followup.FollowupContextBuilder.FollowupContext;
import com.checkupai.report.followup.FollowupContextBuilder.ItemSource;
import com.checkupai.report.followup.FollowupContextBuilder.Ranking;
import com.checkupai.report.retrieval.GuidelineCorpus;
import com.checkupai.report.retrieval.GuidelineProperties;
import com.checkupai.report.retrieval.GuidelineSearch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import tools.jackson.databind.json.JsonMapper;

/** 임시 청크 파일과 점수를 정해 둔 가짜 벡터 저장소로 항목 정하기·고정 근거·범위·절 채우기를 확인한다 */
class FollowupContextBuilderTest {

    private static final String ANNEX = "mohw-2026-6-annex4";

    @TempDir
    Path tempDir;
    private final Map<String, Double> scores = new java.util.HashMap<>();
    private FollowupContextBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        List<Map<String, Object>> chunks = List.of(
                chunk("mohw-annex4-row-egfr", "판정기준 — eGFR / 질환의심: 60 미만", ANNEX, "검사항목별 판정기준", List.of("EGFR")),
                chunk("mohw-annex4-row-hemoglobin", "판정기준 — 혈색소", ANNEX, "검사항목별 판정기준", List.of("HEMOGLOBIN")),
                chunk("mohw-annex4-row-ldl", "판정기준 — LDL", ANNEX, "검사항목별 판정기준", List.of("LDL_CHOLESTEROL")),
                chunk("mohw-annex4-def-normal-b", "판정 구분 — 정상B(경계): 자가관리 필요", ANNEX, "판정 구분", List.of()),
                chunk("mohw-annex4-def-suspect", "판정 구분 — 일반 질환의심: 추적검사 필요", ANNEX, "판정 구분", List.of()),
                chunk("mohw-annex4-def-suspect-chronic", "판정 구분 — 고혈압·당뇨병·이상지질혈증 질환의심", ANNEX, "판정 구분", List.of()),
                chunk("kdca-5457-types-1", "[만성콩팥병 | 개요-종류] 3b단계: 30~44", "kdca-5457", "개요-종류", List.of("EGFR")),
                chunk("kdca-5457-symptoms-1", "[만성콩팥병 | 증상] " + "부종. ".repeat(300), "kdca-5457", "증상", List.of("EGFR")),
                chunk("kdca-5457-symptoms-2", "[만성콩팥병 | 증상] 빈혈도 생긴다", "kdca-5457", "증상", List.of("EGFR")),
                chunk("kdca-1104-symptoms-1", "[빈혈 | 증상] 어지러움", "kdca-1104", "증상", List.of("HEMOGLOBIN")),
                chunk("kdca-6715-definition-1", "[이상지질혈증 | 개요-정의] LDL 130 이상", "kdca-6715", "개요-정의", List.of("LDL_CHOLESTEROL")));
        Path file = tempDir.resolve("chunks.jsonl");
        var mapper = JsonMapper.builder().build();
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> c : chunks) {
            lines.add(mapper.writeValueAsString(c));
        }
        Files.write(file, lines);
        scores.putAll(Map.of("kdca-5457-types-1", 0.6, "kdca-5457-symptoms-1", 0.7, "kdca-5457-symptoms-2", 0.5,
                "kdca-1104-symptoms-1", 0.65, "kdca-6715-definition-1", 0.4));
        var search = new GuidelineSearch(new ScoredVectorStore(), new GuidelineProperties("unused", 4, 0.5));
        builder = new FollowupContextBuilder(new ScreeningCriteria(), new ItemMentions(), new GuidelineCorpus(file), search);
    }

    @Test
    @DisplayName("고른 항목이 있으면 그 항목의 문서만 범위로 하고, 기준표 행은 결과지 주의 항목까지 넣는다")
    void selectedItemNarrowsScopeButKeepsFlaggedRows() {
        FollowupContext context = builder.build(report(), "이거 무슨 뜻이에요?", List.of(ItemCode.EGFR), 20000, Ranking.VECTOR);

        assertThat(context.itemSource()).isEqualTo(ItemSource.SELECTED);
        assertThat(context.textItems()).containsExactly(ItemCode.EGFR);
        assertThat(ids(context.rankedUnits())).containsExactly("kdca-5457#증상", "kdca-5457#개요-종류");
        // eGFR 44(질환의심), 혈색소 11.5 여성(정상B)이 결과지 주의 항목 → 두 행과 두 판정 구분 정의
        assertThat(ids(context.fixedUnits())).containsExactlyInAnyOrder("mohw-annex4-row-egfr",
                "mohw-annex4-row-hemoglobin", "mohw-annex4-def-normal-b", "mohw-annex4-def-suspect");
    }

    @Test
    @DisplayName("벡터 순위: 절 점수는 청크 점수의 최댓값이고, 예산을 넘는 절은 건너뛰고 다음 절을 채운다")
    void packsSectionsWithinBudget() {
        FollowupContext context = builder.build(report(), "콩팥이 안 좋은 건가요?", null, 200, Ranking.VECTOR);

        assertThat(context.itemSource()).isEqualTo(ItemSource.MENTIONED);
        // 증상 절(0.7, 900자 넘음)은 예산 200자를 넘어 건너뛰고 개요-종류(0.6)를 넣는다
        assertThat(ids(context.rankedUnits())).containsExactly("kdca-5457#개요-종류");
        assertThat(context.rankedChars()).isLessThanOrEqualTo(200);
        ContextUnit symptoms = builder.build(report(), "콩팥", null, 20000, Ranking.VECTOR).rankedUnits().getFirst();
        assertThat(symptoms.id()).isEqualTo("kdca-5457#증상");
        assertThat(symptoms.vectorScore()).isEqualTo(0.7);
        assertThat(symptoms.text()).startsWith("[만성콩팥병 | 증상]").endsWith("빈혈도 생긴다")
                .containsOnlyOnce("[만성콩팥병 | 증상]");  // 머리말은 한 번만
    }

    @Test
    @DisplayName("질문에 항목 이름이 없으면 결과지의 주의 항목으로 정하고, 그것도 없으면 전체 문서를 범위로 한다")
    void fallsBackToFlaggedThenAll() {
        FollowupContext flagged = builder.build(report(), "결과값이 어려운데 뭔가요", null, 20000);
        assertThat(flagged.itemSource()).isEqualTo(ItemSource.FLAGGED);
        assertThat(flagged.textItems()).containsExactly(ItemCode.HEMOGLOBIN, ItemCode.EGFR);
        assertThat(ids(flagged.rankedUnits())).doesNotContain("kdca-6715#개요-정의");

        FollowupContext none = builder.build(null, "검진 비용은 얼마예요?", null, 20000);
        assertThat(none.itemSource()).isEqualTo(ItemSource.NONE);
        assertThat(none.fixedUnits()).isEmpty();
        assertThat(ids(none.rankedUnits())).contains("kdca-6715#개요-정의", "kdca-1104#증상");
    }

    @Test
    @DisplayName("서비스 순위는 BM25다: 질문의 글자와 겹치는 절이 벡터 점수가 낮아도 앞에 온다. 겹치는 글자가 없으면 벡터 순서다")
    void bm25RanksByCharacterOverlap() {
        // 벡터는 증상(0.7)을 개요-종류(0.6)보다 앞에 두지만, 질문의 "단계"는 개요-종류("3b단계")에만 있다
        FollowupContext context = builder.build(report(), "몇 단계예요?", List.of(ItemCode.EGFR), 20000);
        assertThat(context.ranking()).isEqualTo(Ranking.BM25);
        assertThat(ids(context.rankedUnits())).containsExactly("kdca-5457#개요-종류", "kdca-5457#증상");
        assertThat(context.rankedUnits().getFirst().score()).isPositive();

        // "어때요"는 어느 절과도 겹치지 않는다(항목 이름 "신사구체여과율(eGFR)"도 절 글자와 겹치지 않게 만든 청크다)
        FollowupContext noOverlap = builder.build(report(), "어때요", List.of(ItemCode.EGFR), 20000);
        assertThat(ids(noOverlap.rankedUnits())).containsExactly("kdca-5457#증상", "kdca-5457#개요-종류");
    }

    @Test
    @DisplayName("이상지질혈증 항목의 질환의심은 만성질환 질환의심 정의를 쓴다")
    void chronicItemsUseChronicDefinition() {
        CheckupReport report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, 50,
                List.of(new ReportItem(ItemCode.LDL_CHOLESTEROL, "LDL", "206", "mg/dL", null, null)));
        FollowupContext context = builder.build(report, "LDL만 높으면 신경 써야 하나요?", null, 20000);

        assertThat(ids(context.fixedUnits())).containsExactlyInAnyOrder("mohw-annex4-row-ldl",
                "mohw-annex4-def-suspect-chronic");
    }

    private static List<String> ids(List<ContextUnit> units) {
        return units.stream().map(ContextUnit::id).toList();
    }

    private static CheckupReport report() {
        return new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.FEMALE, 60, List.of(
                new ReportItem(ItemCode.EGFR, "eGFR", "44", "mL/min/1.73m²", null, null),
                new ReportItem(ItemCode.HEMOGLOBIN, "혈색소", "11.5", "g/dL", null, null),
                new ReportItem(ItemCode.FASTING_GLUCOSE, "공복혈당", "90", "mg/dL", null, null)));
    }

    private static Map<String, Object> chunk(String id, String text, String docId, String section, List<String> codes) {
        return Map.of("id", id, "text", text, "metadata", Map.of("doc_id", docId, "title", docId, "section", section,
                "item_codes", codes));
    }

    /** 질의와 무관하게 정해 둔 점수로 모든 청크를 돌려준다 */
    private final class ScoredVectorStore implements VectorStore {
        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> ids) {
        }

        @Override
        public void delete(Filter.Expression expression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return scores.entrySet().stream()
                    .map(e -> Document.builder().id(e.getKey()).text(e.getKey()).score(e.getValue()).build())
                    .toList();
        }
    }

    @Test
    @DisplayName("항목이 둘 이상이면 항목마다 돌아가며 다음 순위 절을 고른다 (한 항목이 예산을 다 차지하지 않게)")
    void roundRobinAcrossItems() {
        var a1 = unitOf("a1"); var a2 = unitOf("a2"); var a3 = unitOf("a3"); var b1 = unitOf("b1"); var both = unitOf("ab");
        java.util.Map<String, java.util.Set<ItemCode>> items = java.util.Map.of(
                "a1", java.util.Set.of(ItemCode.WAIST_CIRCUMFERENCE), "a2", java.util.Set.of(ItemCode.WAIST_CIRCUMFERENCE),
                "a3", java.util.Set.of(ItemCode.WAIST_CIRCUMFERENCE), "b1", java.util.Set.of(ItemCode.FASTING_GLUCOSE),
                "ab", java.util.Set.of(ItemCode.WAIST_CIRCUMFERENCE, ItemCode.FASTING_GLUCOSE));
        var order = FollowupContextBuilder.roundRobin(java.util.List.of(a1, a2, a3, both, b1),
                java.util.EnumSet.of(ItemCode.WAIST_CIRCUMFERENCE, ItemCode.FASTING_GLUCOSE), items);
        org.assertj.core.api.Assertions.assertThat(order.stream().map(FollowupContextBuilder.ContextUnit::id))
                .containsExactly("a1", "ab", "a2", "b1", "a3");
    }

    private static FollowupContextBuilder.ContextUnit unitOf(String id) {
        return new FollowupContextBuilder.ContextUnit(id, "doc", "t", "s", 0, 0, java.util.List.of(), id);
    }
}
