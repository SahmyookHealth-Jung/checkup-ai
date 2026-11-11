package com.checkupai.report.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추출 파이프라인 설정.
 *
 * @param pipeline baseline: 구조화 출력만 (기준선 재현용) / validated: 검증 + 누락 조각 재요청
 * @param firstPassMaxTokens 1차 추출 출력 상한. 정상 출력은 1,400~1,800토큰. 반복 루프가 문맥을 다 채우는 것을 막는다
 * @param retryMaxTokens 재요청 출력 상한. 누락 조각 몇 개만 다시 뽑으므로 작게 둔다
 * @param retryBatchSize 재요청 한 번에 보내는 누락 조각 수. 조각이 많으면 나눠 보낸다.
 *        2026-11-09 heldout-v1: 1차가 표 하나를 통째로 건너뛰면 조각이 10개 넘게 쌓여 재요청이 출력 상한에서 잘렸다
 * @param retryEnabled 누락 조각을 재요청할지. ADR 0005: 작은 로컬 모델(오프라인 모드)에만 켠다.
 *        Haiku 5.5에서는 이득 없이 비용·지연만 늘었고, 3개년 표에서 지난해 값을 가져와 빈 값을 틀린 값으로 바꿨다.
 *        끄면 누락 조각은 진단 기록에만 남긴다. 단, 1차 출력이 상한에서 잘렸으면 끈 상태에서도 재요청한다(ADR 0004 4절)
 * @param modelSettings 결과를 바꾸는 모델 공급자 설정의 요약 (예: thinking=disabled). 이 모듈은 공급자 설정을 볼 수 없으므로
 *        app 설정이 같은 값을 적어 준다. 평가 캐시 키에 들어간다
 */
@ConfigurationProperties("checkupai.extraction")
public record ExtractionProperties(
        @DefaultValue("validated") Pipeline pipeline,
        @DefaultValue("2500") int firstPassMaxTokens,
        @DefaultValue("600") int retryMaxTokens,
        @DefaultValue("4") int retryBatchSize,
        @DefaultValue("false") boolean retryEnabled,
        @DefaultValue("") String modelSettings) {

    /** 평가 캐시 키와 응답 지표에 들어가는 파이프라인 이름. 재요청을 끄면 결과가 다르므로 이름도 다르다 */
    public String pipelineLabel() {
        return pipeline == Pipeline.VALIDATED && !retryEnabled ? pipeline.label() + "-noretry" : pipeline.label();
    }

    /**
     * 같은 파이프라인 버전이라도 결과를 바꾸는 설정의 요약. 평가 캐시 키에 들어간다.
     * 2026-11-10 리뷰: 출력 상한이나 thinking을 바꿔도 캐시 키가 같아 이전 결과를 그대로 재사용할 수 있었다.
     */
    public String settingsLabel() {
        String extraction = "firstPassMaxTokens=" + firstPassMaxTokens + ",retryMaxTokens=" + retryMaxTokens
                + ",retryBatchSize=" + retryBatchSize;
        return modelSettings == null || modelSettings.isBlank() ? extraction : extraction + "," + modelSettings;
    }

    public enum Pipeline {
        BASELINE("baseline"),
        VALIDATED("validated-v2.6");  // 캐시 키에 들어가므로 검증 로직을 바꾸면 올린다

        private final String label;

        Pipeline(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
