package com.checkupai.report.extraction;

import com.checkupai.report.extraction.ExtractionValidator.Issue;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 추출 결과와 호출 측정값.
 *
 * @param report 추출한 결과지
 * @param metrics 모델, 프롬프트·파이프라인 버전, 지연시간, 토큰, 검증 결과 (평가·비용 분석용)
 * @param diagnostics 원인 분석용 상세 기록. 요청에 diagnostics=true일 때만 채운다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractionResult(CheckupReport report, Metrics metrics, Diagnostics diagnostics) {

    /**
     * @param attempts LLM 호출 횟수 (재요청 포함)
     * @param retriedSegments 누락 의심으로 다시 보낸 원문 조각 수
     * @param issues 검증에서 발견한 문제 유형별 건수
     * @param firstPassLatencyMs 1차 호출까지의 지연. 모델 비교에서 "1차만" 조건의 지연·비용을 따로 보려고 남긴다
     * @param firstPassPromptTokens 1차 호출의 입력 토큰
     * @param firstPassCompletionTokens 1차 호출의 출력 토큰
     */
    public record Metrics(
            String model,
            String promptVersion,
            String pipeline,
            long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            int attempts,
            int retriedSegments,
            Map<String, Integer> issues,
            long firstPassLatencyMs,
            Integer firstPassPromptTokens,
            Integer firstPassCompletionTokens) {
    }

    /**
     * v1에서 건수만 남겨 원인을 확정하지 못한 문제를 고치려고 추가했다.
     * firstPassItems가 있으면 같은 실행 안에서 "1차 추출 vs 최종 결과"를 짝지어 비교할 수 있다.
     *
     * @param firstPassItems 1차 추출 원본 (검증 전)
     * @param issues 검증 문제 상세
     * @param retryInput 표 머리 줄과 누락 의심 조각. 재요청을 껐으면 보내지 않고 기록만 한다 (ADR 0005)
     * @param retryItems 재요청 응답 원본 (검증 전)
     * @param retryError 재요청이 실패했을 때의 오류 (실패해도 1차 결과는 반환한다)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Diagnostics(
            List<ReportItem> firstPassItems,
            List<Issue> issues,
            List<String> retryInput,
            List<ReportItem> retryItems,
            String retryError) {
    }
}
