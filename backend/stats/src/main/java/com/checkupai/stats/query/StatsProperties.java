package com.checkupai.stats.query;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 기능 2 Text-to-SQL 설정 (ADR 0013).
 *
 * @param url 통계 DB 주소
 * @param username 읽기 전용 계정 (stats 스키마만 SELECT, 읽기 전용 트랜잭션, 문장 시간 제한은 계정에 걸려 있다)
 * @param password .env의 STATS_READER_PASSWORD
 * @param maxRows 결과 행 상한
 * @param minGroupSize 사람 수(n)가 이보다 적은 행의 값은 숨긴다
 * @param maxTokens SQL 생성 출력 상한
 */
@ConfigurationProperties("checkupai.stats")
public record StatsProperties(String url, String username, String password,
                              @DefaultValue("200") int maxRows,
                              @DefaultValue("10") int minGroupSize,
                              @DefaultValue("1200") int maxTokens) {
}
