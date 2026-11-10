package com.checkupai.report.retrieval;

import java.util.Map;

/**
 * 검색된 근거 청크.
 *
 * @param score 질의와의 유사도 (코사인 유사도, 1에 가까울수록 비슷하다)
 * @param metadata 문서명(title), 발행기관(publisher), 판(edition), 절(section), url, 라이선스, 관련 검진 항목
 */
public record RetrievedChunk(String id, String text, double score, Map<String, Object> metadata) {

    public String metadataText(String key) {
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
