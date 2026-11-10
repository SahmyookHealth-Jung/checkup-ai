package com.checkupai.report.retrieval;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * 해설 근거 검색 (ADR 0007 3절). 4단계 기준선은 벡터 유사도 상위 k개뿐이다.
 * 5단계(ADR 0008 4절 4번)에서 항목 필터({@link #searchForItem})와 판정기준 행 조회({@link #criteriaRow})를 더했다.
 * 둘 다 켜고 끌 수 있게 해설 서비스가 고른다 (짝비교).
 */
@Service
public class GuidelineSearch {

    /** 판정기준표 행 청크의 절 이름 (data/guidelines/curated/mohw-2026-6-annex4.jsonl) */
    static final String CRITERIA_ROW_SECTION = "검사항목별 판정기준";
    /** 항목 필터를 걸어도 top-k를 채우도록 넉넉히 가져올 배수 (청크가 100개 미만이라 비용이 작다) */
    private static final int OVERFETCH = 5;

    private final VectorStore vectorStore;
    private final GuidelineProperties properties;

    public GuidelineSearch(VectorStore vectorStore, GuidelineProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public SearchResult search(String query) {
        return search(query, properties.topK());
    }

    public SearchResult search(String query, int topK) {
        long started = System.nanoTime();
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build());
        List<RetrievedChunk> chunks = documents == null ? List.of() : documents.stream()
                .map(document -> new RetrievedChunk(document.getId(), document.getText(),
                        document.getScore() == null ? 0.0 : document.getScore(), document.getMetadata()))
                .toList();
        return new SearchResult(query, chunks, (System.nanoTime() - started) / 1_000_000);
    }

    /** 근거로 쓸 만큼 비슷한 청크만 남긴다 (가드레일: 남는 것이 없으면 "근거 없음") */
    public List<RetrievedChunk> relevant(SearchResult result) {
        return result.chunks().stream().filter(chunk -> chunk.score() >= properties.minSimilarity()).toList();
    }

    /**
     * 항목 필터 (ADR 0008 4절 4번의 1): 그 항목 코드를 가졌거나, 항목 코드가 비어 있는(판정 구분 정의 같은) 청크만 남긴다.
     * 다른 항목의 기준표 행이나 다른 질환의 글이 남는 자리를 채우지 못하게 한다. 걸러도 top-k를 채우도록 넉넉히 가져와 거른다.
     */
    public SearchResult searchForItem(String query, String itemCode) {
        SearchResult wide = search(query, properties.topK() * OVERFETCH);
        List<RetrievedChunk> kept = wide.chunks().stream()
                .filter(chunk -> allowsItem(chunk, itemCode))
                .limit(properties.topK())
                .toList();
        return new SearchResult(query, kept, wide.latencyMs());
    }

    static boolean allowsItem(RetrievedChunk chunk, String itemCode) {
        Object codes = chunk.metadata().get("item_codes");
        if (!(codes instanceof Collection<?> list) || list.isEmpty()) {
            return true;
        }
        return list.stream().anyMatch(code -> itemCode.equals(String.valueOf(code)));
    }

    /**
     * 그 항목의 판정기준표 행 (코드 문구의 출처, ADR 0008 1절). 유사도와 상관없이 정해진 행을 찾는다.
     * 판정기준표 행만 가져와(절 이름 필터) 항목 코드가 맞는 행을 고른다. 없으면 비어 있다 (해설은 '근거 없음').
     */
    public Optional<RetrievedChunk> criteriaRow(String itemCode, String query) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(50)
                .filterExpression(new FilterExpressionBuilder().eq("section", CRITERIA_ROW_SECTION).build())
                .build());
        return (documents == null ? List.<Document>of() : documents).stream()
                .map(document -> new RetrievedChunk(document.getId(), document.getText(),
                        document.getScore() == null ? 0.0 : document.getScore(), document.getMetadata()))
                .filter(chunk -> CRITERIA_ROW_SECTION.equals(chunk.metadataText("section")))
                .filter(chunk -> chunk.metadata().get("item_codes") instanceof Collection<?> codes
                        && codes.stream().anyMatch(code -> itemCode.equals(String.valueOf(code))))
                .max(Comparator.comparingDouble(RetrievedChunk::score));
    }
}
