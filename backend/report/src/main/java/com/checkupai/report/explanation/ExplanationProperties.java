package com.checkupai.report.explanation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 해설 설정 (ADR 0007).
 *
 * @param maxItems 해설할 항목 수 상한. 질환의심을 먼저 고른다
 * @param maxTokens 해설 출력 상한. 2026-11-10: 8개 항목 해설이 2,000토큰에서 잘렸다 (한국어는 토큰이 많다)
 */
@ConfigurationProperties("checkupai.explanation")
public record ExplanationProperties(@DefaultValue("8") int maxItems, @DefaultValue("4000") int maxTokens) {
}
