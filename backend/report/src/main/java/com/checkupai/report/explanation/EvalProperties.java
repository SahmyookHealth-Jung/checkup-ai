package com.checkupai.report.explanation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 평가 전용 API 설정 (ADR 0008 4절 2번).
 *
 * @param endpointsEnabled 저장한 추출 결과로 해설만 돌리는 API를 연다. 추출 검증을 거치지 않은 값이 LLM에 가므로
 *        로컬 평가에서만 켜고, 공개 배포에서는 끈다
 */
@ConfigurationProperties("checkupai.eval")
public record EvalProperties(@DefaultValue("false") boolean endpointsEnabled) {
}
