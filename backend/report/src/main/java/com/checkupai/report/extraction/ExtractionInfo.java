package com.checkupai.report.extraction;

/**
 * 현재 추출 파이프라인의 식별 정보. 평가 스크립트가 캐시 키와 결과 라벨로 쓴다.
 *
 * @param model 채팅 모델 이름 (예: qwen3:4b)
 * @param promptVersion 프롬프트 버전 (예: extraction-v1)
 * @param pipeline 파이프라인 버전 (예: baseline, validated-v2.4, validated-v2.4-noretry)
 * @param settings 결과를 바꾸는 설정 요약 (출력 상한, 재요청 묶음 크기, 모델 공급자 설정). 캐시 키가 설정 변경을 구분하게 한다
 */
public record ExtractionInfo(String model, String promptVersion, String pipeline, String settings) {
}
