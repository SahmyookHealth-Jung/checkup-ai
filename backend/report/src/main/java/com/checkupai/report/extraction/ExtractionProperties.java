package com.checkupai.report.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추출 파이프라인 설정.
 *
 * @param pipeline baseline: 구조화 출력만 (기준선 재현용) / validated: 검증 + 누락 조각 재요청
 * @param firstPassMaxTokens 1차 추출 출력 상한. 정상 출력은 1,400~1,800토큰. 반복 루프가 문맥을 다 채우는 것을 막는다
 * @param retryMaxTokens 재요청 출력 상한. 누락 조각 몇 개만 다시 뽑으므로 작게 둔다
 */
@ConfigurationProperties("checkupai.extraction")
public record ExtractionProperties(
        @DefaultValue("validated") Pipeline pipeline,
        @DefaultValue("2500") int firstPassMaxTokens,
        @DefaultValue("600") int retryMaxTokens) {

    public enum Pipeline {
        BASELINE("baseline"),
        VALIDATED("validated-v2");

        private final String label;

        Pipeline(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
