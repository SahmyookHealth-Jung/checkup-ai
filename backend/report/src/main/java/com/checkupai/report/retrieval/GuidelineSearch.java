package com.checkupai.report.retrieval;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * 해설 근거 검색 (ADR 0007 3절). 4단계 기준선은 벡터 유사도 상위 k개뿐이다.
 * 메타데이터(검진 항목) 필터와 키워드 검색은 5단계에서 효과를 비교하려고 일부러 쓰지 않는다.
 */
@Service
public class GuidelineSearch {

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
}
