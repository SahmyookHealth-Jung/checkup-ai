package com.checkupai.stats.query;

/**
 * LLM이 내는 SQL 초안 (구조화 출력). 실행할지는 코드가 정한다 ({@link StatsService}).
 *
 * @param decision ANSWER(SQL로 답함), NO_DATA(데이터에 없음), REFUSE(개인 단위 조회, 쓰기, 시스템 정보 등 막아야 할 요청)
 * @param sql ANSWER일 때 SELECT 한 문장
 * @param reason 답하지 않을 때 이유 한 문장, 답할 때는 SQL이 무엇을 세는지 한 문장
 */
public record SqlDraft(String decision, String sql, String reason) {
}
