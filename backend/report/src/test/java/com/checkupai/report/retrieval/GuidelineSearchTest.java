package com.checkupai.report.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

class GuidelineSearchTest {

    private static final Document TG_ROW = doc("mohw-annex4-row-triglycerides", "검사항목별 판정기준", List.of("TRIGLYCERIDES"), 0.70);
    private static final Document AST_ROW = doc("mohw-annex4-row-ast", "검사항목별 판정기준", List.of("AST"), 0.66);
    private static final Document DEF_SUSPECT = doc("mohw-annex4-def-suspect", "판정 구분", List.of(), 0.64);
    private static final Document DIABETES = doc("kdca-5305-checkup-1", "정기 진찰", List.of("FASTING_GLUCOSE"), 0.62);
    private static final Document ALT_ROW = doc("mohw-annex4-row-alt", "검사항목별 판정기준", List.of("ALT"), 0.60);

    private final GuidelineSearch search = new GuidelineSearch(
            new FixedVectorStore(List.of(TG_ROW, AST_ROW, DEF_SUSPECT, DIABETES, ALT_ROW)),
            new GuidelineProperties("unused", 4, 0.5));

    @Test
    @DisplayName("항목 필터는 다른 항목·다른 질환의 근거를 빼고, 그 항목의 근거와 항목 코드가 없는 정의는 남긴다")
    void itemFilterKeepsOwnAndGenericChunks() {
        SearchResult result = search.searchForItem("AST 질환의심", "AST");

        assertThat(result.chunks()).extracting(RetrievedChunk::id)
                .containsExactly("mohw-annex4-row-ast", "mohw-annex4-def-suspect");
    }

    @Test
    @DisplayName("필터 없는 검색은 기준선 그대로 유사도 상위 k개다")
    void plainSearchIsUnchanged() {
        assertThat(search.search("AST 질환의심").chunks()).hasSize(5);  // 가짜 저장소는 topK를 무시한다
    }

    @Test
    @DisplayName("항목 코드 메타데이터가 없으면 정의 청크처럼 통과시킨다")
    void missingItemCodesPasses() {
        var chunk = new RetrievedChunk("x", "t", 0.9, Map.of());
        assertThat(GuidelineSearch.allowsItem(chunk, "AST")).isTrue();
    }

    private static Document doc(String id, String section, List<String> itemCodes, double score) {
        return Document.builder().id(id).text(id).score(score)
                .metadata(Map.of("section", section, "item_codes", itemCodes)).build();
    }

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
}
