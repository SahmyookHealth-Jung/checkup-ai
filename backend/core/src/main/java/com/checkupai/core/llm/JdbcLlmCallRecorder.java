package com.checkupai.core.llm;

import java.sql.Timestamp;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code llm_call_log} 테이블에 호출 기록을 남긴다 (테이블은 app 모듈 Flyway V2) */
public class JdbcLlmCallRecorder implements LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(JdbcLlmCallRecorder.class);

    private final JdbcClient jdbc;

    public JdbcLlmCallRecorder(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void record(LlmCallRecord call) {
        try {
            jdbc.sql("""
                            INSERT INTO llm_call_log (called_at, feature, model, input_tokens, output_tokens, latency_ms,
                                                      cost_usd, status, error_type, redactions)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                            """)
                    .params(Timestamp.from(call.calledAt()), call.feature(), call.model(), call.inputTokens(),
                            call.outputTokens(), call.latencyMs(), call.costUsd(), call.status().name(),
                            call.errorType(), toJson(call.redactions()))
                    .update();
        } catch (RuntimeException e) {
            // 기록 실패가 사용자 요청을 실패시키면 안 된다. 경고만 남긴다
            log.warn("LLM 호출 기록 저장 실패 ({}): {}", call.feature(), e.getMessage());
        }
    }

    /** 키는 식별정보 종류 이름(영문 대문자), 값은 정수뿐이라 직접 만든다 */
    static String toJson(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "{}";
        }
        return new TreeMap<>(counts).entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
