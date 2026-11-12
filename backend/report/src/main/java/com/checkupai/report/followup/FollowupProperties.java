package com.checkupai.report.followup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 후속 질문 설정 (ADR 0011).
 *
 * @param budgetChars 순위대로 채우는 근거 절의 글자 수 상한. 판정기준표 행과 판정 구분 정의는 예산 밖이다.
 *        8천 자와 1만6천 자 중 개발 몫에서 정한다 (ADR 0011 2절 5). 정하기 전 기본값은 8천 자다
 */
@ConfigurationProperties("checkupai.followup")
public record FollowupProperties(@DefaultValue("8000") int budgetChars) {
}
