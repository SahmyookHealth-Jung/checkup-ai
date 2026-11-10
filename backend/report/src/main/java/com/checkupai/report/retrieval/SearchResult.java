package com.checkupai.report.retrieval;

import java.util.List;

/** @param latencyMs 질의 임베딩(로컬 bge-m3) + 벡터 검색에 걸린 시간 */
public record SearchResult(String query, List<RetrievedChunk> chunks, long latencyMs) {
}
