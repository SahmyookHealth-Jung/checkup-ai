package com.checkupai.core.llm;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code llm_call_log} 집계 (ADR 0012). 운영 화면과 데모 예산 상한이 쓴다.
 * 기록에는 원문이 없으므로 메타데이터(기능, 모델, 토큰, 비용, 지연, 상태)만 다룬다 (가드레일 원칙 7).
 */
public class LlmUsage {

    public record FeatureUsage(String feature, long calls, long errors, long inputTokens, long outputTokens,
                               BigDecimal costUsd, long p50LatencyMs, long p95LatencyMs) {
    }

    public record DailyUsage(LocalDate day, String feature, long calls, BigDecimal costUsd) {
    }

    private final JdbcClient jdbc;
    private final ZoneId zone;

    public LlmUsage(DataSource dataSource, ZoneId zone) {
        this.jdbc = JdbcClient.create(dataSource);
        this.zone = zone;
    }

    public Instant startOfToday() {
        return LocalDate.now(zone).atStartOfDay(zone).toInstant();
    }

    public Instant startOfDaysAgo(int days) {
        return LocalDate.now(zone).minusDays(days - 1L).atStartOfDay(zone).toInstant();
    }

    public BigDecimal costSince(Instant since) {
        return jdbc.sql("SELECT COALESCE(SUM(cost_usd), 0) FROM llm_call_log WHERE called_at >= ?")
                .param(Timestamp.from(since))
                .query(BigDecimal.class)
                .single();
    }

    public List<FeatureUsage> byFeature(Instant since) {
        return jdbc.sql("""
                        SELECT feature, COUNT(*) AS calls, COUNT(*) FILTER (WHERE status <> 'SUCCESS') AS errors,
                               COALESCE(SUM(input_tokens), 0) AS input_tokens, COALESCE(SUM(output_tokens), 0) AS output_tokens,
                               COALESCE(SUM(cost_usd), 0) AS cost_usd,
                               COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY latency_ms), 0) AS p50,
                               COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95
                        FROM llm_call_log WHERE called_at >= ?
                        GROUP BY feature ORDER BY cost_usd DESC, calls DESC
                        """)
                .param(Timestamp.from(since))
                .query((rs, n) -> new FeatureUsage(rs.getString("feature"), rs.getLong("calls"), rs.getLong("errors"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getBigDecimal("cost_usd"),
                        Math.round(rs.getDouble("p50")), Math.round(rs.getDouble("p95"))))
                .list();
    }

    public List<DailyUsage> daily(Instant since) {
        return jdbc.sql("""
                        SELECT (called_at AT TIME ZONE ?)::date AS day, feature, COUNT(*) AS calls,
                               COALESCE(SUM(cost_usd), 0) AS cost_usd
                        FROM llm_call_log WHERE called_at >= ?
                        GROUP BY 1, 2 ORDER BY 1, 2
                        """)
                .params(zone.getId(), Timestamp.from(since))
                .query((rs, n) -> new DailyUsage(rs.getObject("day", LocalDate.class), rs.getString("feature"),
                        rs.getLong("calls"), rs.getBigDecimal("cost_usd")))
                .list();
    }
}
