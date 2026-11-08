package com.checkupai.report.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추출 파이프라인 설정.
 *
 * @param pipeline baseline: 구조화 출력만 (기준선 재현용) / validated: 검증 + 누락 줄 재요청
 */
@ConfigurationProperties("checkupai.extraction")
public record ExtractionProperties(@DefaultValue("validated") Pipeline pipeline) {

    public enum Pipeline {
        BASELINE("baseline"),
        VALIDATED("validated-v1");

        private final String label;

        Pipeline(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
