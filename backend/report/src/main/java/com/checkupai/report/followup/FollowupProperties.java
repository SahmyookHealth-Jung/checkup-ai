package com.checkupai.report.followup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 후속 질문 설정 (ADR 0011).
 *
 * @param budgetChars 순위대로 채우는 근거 절의 글자 수 상한. 판정기준표 행과 판정 구분 정의는 예산 밖이다.
 *        개발 몫에서 8천 자로 정했다 (ADR 0011 결과, 2026-11-12, 1만6천 자보다 정답 절을 더 담았다)
 */
@ConfigurationProperties("checkupai.followup")
public record FollowupProperties(@DefaultValue("8000") int budgetChars) {
}
