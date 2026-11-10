package com.checkupai.core.llm;

/** LLM 호출 기록 저장소. 저장이 실패해도 LLM 호출 자체를 실패시키지 않는다 (구현이 예외를 삼킨다) */
public interface LlmCallRecorder {

    void record(LlmCallRecord call);
}
