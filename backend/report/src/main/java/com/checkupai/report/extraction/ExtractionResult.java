package com.checkupai.report.extraction;

/**
 * 추출 결과와 호출 측정값.
 *
 * @param report 추출한 결과지
 * @param metrics 모델, 프롬프트 버전, 지연시간, 토큰 (평가·비용 분석용)
 */
public record ExtractionResult(CheckupReport report, Metrics metrics) {

    public record Metrics(
            String model,
            String promptVersion,
            long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            int attempts) {
    }
}
