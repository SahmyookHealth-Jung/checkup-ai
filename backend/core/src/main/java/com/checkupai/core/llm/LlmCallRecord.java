package com.checkupai.core.llm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * LLM 호출 한 번의 메타데이터 (ADR 0006). 프롬프트와 응답 원문은 남기지 않는다 (의료 가드레일 원칙 7).
 *
 * @param costUsd 가격표에 없는 모델이면 null
 * @param redactions 외부로 보내기 전에 지운 식별정보의 종류별 개수 (값은 남기지 않는다)
 */
public record LlmCallRecord(
        Instant calledAt,
        String feature,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        long latencyMs,
        BigDecimal costUsd,
        Status status,
        String errorType,
        Map<String, Integer> redactions) {

    public enum Status {
        SUCCESS, ERROR
    }
}
