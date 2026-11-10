package com.checkupai.report.explanation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 해설 설정 (ADR 0007).
 *
 * @param maxItems 해설할 항목 수 상한. 질환의심을 먼저 고른다
 * @param maxTokens 해설 출력 상한. 2026-11-10: 8개 항목 해설이 2,000토큰에서 잘렸다 (한국어는 토큰이 많다)
 * @param itemFilter 항목 필터 (ADR 0008 4절 4번의 1). 그 항목의 근거와 항목 코드가 없는 근거만 쓴다
 * @param codeTemplates 근거가 판정기준표 1행뿐인 항목은 LLM 대신 코드 문구로 답한다 (ADR 0008 1절)
 */
@ConfigurationProperties("checkupai.explanation")
public record ExplanationProperties(@DefaultValue("8") int maxItems, @DefaultValue("4000") int maxTokens,
                                    @DefaultValue("false") boolean itemFilter,
                                    @DefaultValue("false") boolean codeTemplates) {
}
