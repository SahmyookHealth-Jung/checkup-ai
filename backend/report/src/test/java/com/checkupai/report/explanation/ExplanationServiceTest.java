package com.checkupai.report.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.ReportItem;
import com.checkupai.report.extraction.ReviewItem;
import com.checkupai.report.retrieval.GuidelineProperties;
import com.checkupai.report.retrieval.GuidelineSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/** 가짜 벡터 저장소와 가짜 모델로 판정 → 검색 → 생성 → 검증 흐름을 확인한다 */
class ExplanationServiceTest {

    private static final Document GLUCOSE_ROW = doc("mohw-annex4-row-fasting-glucose",
            "검사항목별 판정기준 — 공복 혈당 / 정상A: 100 미만 / 정상B(경계): 100-125 / 질환의심: 126 이상", 0.70);
    private static final Document WEAK = doc("kdca-5305-types-1", "[당뇨병 | 개요-종류] 1형당뇨병은 ...", 0.30);

    @Test
    @DisplayName("질환의심 항목만 해설하고, 진료 권고·확인 필요·고지문은 코드가 붙인다. 인용은 번호로 바뀐다")
    void explainsSuspectItemWithCodeGuardrails() {
        var model = new ScriptedChatModel("""
                {"items":[{"itemCode":"FASTING_GLUCOSE","sentences":[
                  {"text":"공복혈당 183 mg/dL은 판정기준의 126 이상 구간입니다.","sources":["S1"]},
                  {"text":"하루 3번 운동하세요.","sources":["S9"]}]}]}""");
        var service = service(model, List.of(GLUCOSE_ROW, WEAK));

        ExplanationResult result = service.explain(extraction(
                List.of(item(ItemCode.FASTING_GLUCOSE, "183", "mg/dL"), item(ItemCode.HEMOGLOBIN, "14.0", "g/dL")),
                List.of(new ReviewItem(ItemCode.AST, "AST", "6200", "가능 범위 밖: 6200"))));

        assertThat(result.judgments()).extracting(ExplanationResult.ItemJudgment::judgment)
                .containsExactly(Judgment.SUSPECT, Judgment.NORMAL_A);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo(ItemCode.FASTING_GLUCOSE);
            assertThat(item.sentences()).singleElement().satisfies(s -> assertThat(s.citations()).containsExactly(1));
            assertThat(item.careAdvice()).isEqualTo(ExplanationGuardrails.CARE_ADVICE);
            assertThat(item.criterionSource()).contains("보건복지부고시 제2026-6호");
        });
        assertThat(result.citations()).singleElement()
                .satisfies(c -> assertThat(c.chunkId()).isEqualTo("mohw-annex4-row-fasting-glucose"));
        assertThat(result.needsReview()).singleElement()
                .satisfies(r -> assertThat(r.advice()).isEqualTo(ExplanationGuardrails.REVIEW_ADVICE));
        assertThat(result.disclaimer()).isEqualTo(ExplanationGuardrails.DISCLAIMER);
        assertThat(result.metrics().invalidCitations()).isEqualTo(1);
        assertThat(result.metrics().uncitedSentences()).isEqualTo(1);
        assertThat(model.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("근거 유사도가 임계 미만이면 LLM을 부르지 않고 '근거 없음'으로 답한다")
    void answersNoEvidenceWithoutCallingModel() {
        var model = new ScriptedChatModel("{\"items\":[]}");
        var service = service(model, List.of(WEAK));

        ExplanationResult result = service.explain(extraction(List.of(item(ItemCode.FASTING_GLUCOSE, "130", "mg/dL")), List.of()));

        assertThat(result.items()).singleElement().satisfies(item -> assertThat(item.noEvidence()).isTrue());
        assertThat(model.calls).isZero();
        assertThat(result.disclaimer()).isNotBlank();
    }

    private static ExplanationService service(ChatModel model, List<Document> documents) {
        var properties = new GuidelineProperties("unused", 4, 0.5);
        return new ExplanationService(ChatClient.builder(model), new ScreeningCriteria(),
                new GuidelineSearch(new FixedVectorStore(documents), properties), new ExplanationProperties(8, 2000));
    }

    private static ExtractionResult extraction(List<ReportItem> items, List<ReviewItem> review) {
        var report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, 50, items);
        return new ExtractionResult(report, null, null, review);
    }

    private static ReportItem item(ItemCode code, String value, String unit) {
        return new ReportItem(code, code.koreanName(), value, unit, null, null);
    }

    private static Document doc(String id, String text, double score) {
        return Document.builder().id(id).text(text).score(score)
                .metadata(Map.of("title", "건강검진 실시기준", "publisher", "보건복지부", "section", "검사항목별 판정기준"))
                .build();
    }

    /** 질의와 무관하게 정해 둔 문서를 돌려주는 가짜 벡터 저장소 */
    private record FixedVectorStore(List<Document> documents) implements VectorStore {
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
            return documents;
        }
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final String response;
        private int calls;

        ScriptedChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            return new ChatResponse(new ArrayList<>(List.of(new Generation(new AssistantMessage(response)))));
        }
    }
}
