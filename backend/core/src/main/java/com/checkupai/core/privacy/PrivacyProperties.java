package com.checkupai.core.privacy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 외부 전송 전 식별정보 제거 설정 (ADR 0006).
 *
 * @param redactionEnabled 항상 켠다. 끄는 것은 "제거가 결과에 주는 영향"을 재는 실험용이다
 *        (2026-11-10: Haiku가 일찍 멈춘 원인이 식별정보 표시인지 가르는 실험). 운영 설정에서 끄지 않는다
 */
@ConfigurationProperties("checkupai.privacy")
public record PrivacyProperties(@DefaultValue("true") boolean redactionEnabled) {
}
