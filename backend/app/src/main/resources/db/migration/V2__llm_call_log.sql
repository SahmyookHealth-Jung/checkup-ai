-- LLM 호출 기록 (ADR 0006). 메타데이터만 남긴다: 프롬프트·응답 원문과 지운 식별정보의 값은 저장하지 않는다
-- (의료 가드레일 원칙 7). 기능별·모델별 비용과 지연을 비교하는 데 쓴다.
CREATE TABLE llm_call_log (
    id            BIGSERIAL     PRIMARY KEY,
    called_at     TIMESTAMPTZ   NOT NULL,
    feature       VARCHAR(100)  NOT NULL,              -- 예: report.extraction.first-pass
    model         VARCHAR(100),
    input_tokens  INTEGER,
    output_tokens INTEGER,
    latency_ms    BIGINT        NOT NULL,
    cost_usd      NUMERIC(14, 8),                      -- 가격표에 없는 모델이면 NULL
    status        VARCHAR(20)   NOT NULL,              -- SUCCESS | ERROR
    error_type    VARCHAR(200),
    redactions    JSONB         NOT NULL DEFAULT '{}'  -- 지운 식별정보의 종류별 개수 (예: {"NAME":1})
);

CREATE INDEX idx_llm_call_log_called_at ON llm_call_log (called_at);
CREATE INDEX idx_llm_call_log_feature ON llm_call_log (feature, called_at);
