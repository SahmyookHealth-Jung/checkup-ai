package com.checkupai.report.extraction;

/**
 * 현재 추출 파이프라인의 식별 정보. 평가 스크립트가 캐시 키와 결과 라벨로 쓴다.
 *
 * @param model 채팅 모델 이름 (예: qwen3:4b)
 * @param promptVersion 프롬프트 버전 (예: extraction-v1)
 * @param pipeline 파이프라인 버전 (예: baseline, validated-v1)
 */
public record ExtractionInfo(String model, String promptVersion, String pipeline) {
}
