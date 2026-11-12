package com.checkupai.stats.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlGuardTest {

    @Test
    @DisplayName("허용한 테이블의 집계 SELECT는 통과한다 (조인, WITH, 끝 세미콜론 포함)")
    void allowsAggregates() {
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup")).isEmpty();
        assertThat(SqlGuard.check("SELECT r.name, COUNT(*) AS n FROM stats.checkup c JOIN region r ON r.code = c.region_code GROUP BY r.name;")).isEmpty();
        assertThat(SqlGuard.check("WITH t AS (SELECT sex, fasting_glucose FROM checkup) SELECT sex, AVG(fasting_glucose), COUNT(*) AS n FROM t GROUP BY sex")).isEmpty();
        assertThat(SqlGuard.check("SELECT CASE WHEN sex = 1 THEN '남자' ELSE '여자' END AS s, COUNT(*) AS n FROM checkup GROUP BY sex")).isEmpty();
    }

    @Test
    @DisplayName("쓰기, 여러 문장, 주석, 다른 테이블·시스템 함수, 개인 단위 조회는 막는다")
    void blocks() {
        assertThat(SqlGuard.check("DELETE FROM checkup")).get().asString().contains("SELECT");
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup; DROP TABLE checkup")).get().asString().contains("한 문장");
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup -- x")).get().asString().contains("주석");
        assertThat(SqlGuard.check("SELECT SUM(cost_usd) AS n FROM llm_call_log")).get().asString().contains("llm_call_log");
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM public.llm_call_log")).isPresent();
        assertThat(SqlGuard.check("SELECT usename, COUNT(*) AS n FROM pg_shadow GROUP BY usename")).isPresent();
        assertThat(SqlGuard.check("SELECT set_config('role', 'checkup', false), COUNT(*) AS n FROM checkup")).isPresent();
        assertThat(SqlGuard.check("SELECT height_cm, weight_kg FROM checkup")).get().asString().contains("집계");
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n INTO newtable FROM checkup")).isPresent();
    }

    @Test
    @DisplayName("문자열 안의 단어는 명령으로 보지 않는다")
    void ignoresWordsInStrings() {
        assertThat(SqlGuard.check("SELECT CASE WHEN smoking = 2 THEN 'drop 끊음' END AS s, COUNT(*) AS n FROM checkup GROUP BY smoking")).isEmpty();
    }
}
