package com.checkupai.report.extraction;

import java.util.Map;

/**
 * 추출 결과와 호출 측정값.
 *
 * @param report 추출한 결과지
 * @param metrics 모델, 프롬프트·파이프라인 버전, 지연시간, 토큰, 검증 결과 (평가·비용 분석용)
 */
public record ExtractionResult(CheckupReport report, Metrics metrics) {

    /**
     * @param attempts LLM 호출 횟수 (재요청 포함)
     * @param retriedLines 누락 의심으로 다시 보낸 원문 줄 수
     * @param issues 검증에서 발견한 문제 유형별 건수
     */
    public record Metrics(
            String model,
            String promptVersion,
            String pipeline,
            long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            int attempts,
            int retriedLines,
            Map<String, Integer> issues) {
    }
}
