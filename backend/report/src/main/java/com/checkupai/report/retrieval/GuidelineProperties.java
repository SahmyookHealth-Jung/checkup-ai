package com.checkupai.report.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 해설 근거 검색 설정 (ADR 0007 3절).
 *
 * @param chunksFile 근거 청크 파일 (eval/rag/build_chunks.py가 만든 JSONL)
 * @param topK 검색 기준선: 벡터 유사도 상위 k개
 * @param minSimilarity 이보다 유사도가 낮은 근거는 버린다. 남는 근거가 없으면 "근거 없음"으로 답한다
 */
@ConfigurationProperties("checkupai.guidelines")
public record GuidelineProperties(
        @DefaultValue("../../data/guidelines/chunks/chunks.jsonl") String chunksFile,
        @DefaultValue("4") int topK,
        @DefaultValue("0.5") double minSimilarity) {
}
