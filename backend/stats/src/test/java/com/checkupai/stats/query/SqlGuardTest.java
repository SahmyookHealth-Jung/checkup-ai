package com.checkupai.stats.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlGuardTest {

    @Test
    @DisplayName("허용한 모양의 집계는 통과한다: 묶음·조인, 비율(AVG CASE), 중앙값, 순위, 한국어·따옴표 별칭, 끝 세미콜론")
    void allowsAggregates() {
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup")).isEmpty();
        assertThat(SqlGuard.check("SELECT r.name AS 시도, ROUND(AVG(c.fasting_glucose), 1) AS 평균, COUNT(*) AS n "
                + "FROM stats.checkup c JOIN region r ON r.code = c.region_code WHERE c.fasting_glucose IS NOT NULL "
                + "GROUP BY r.name ORDER BY r.name;")).isEmpty();
        assertThat(SqlGuard.check("SELECT CASE c.sex WHEN 1 THEN '남자' ELSE '여자' END AS \"성별 이름\", "
                + "ROUND(100.0 * AVG(CASE WHEN c.drinking = 1 THEN 1 ELSE 0 END), 1) AS pct, COUNT(*) AS n "
                + "FROM checkup c WHERE c.drinking IS NOT NULL GROUP BY c.sex ORDER BY c.sex")).isEmpty();
        assertThat(SqlGuard.check("SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY hemoglobin) AS med, COUNT(*) AS n "
                + "FROM checkup WHERE hemoglobin IS NOT NULL")).isEmpty();
        assertThat(SqlGuard.check("SELECT r.name, a.label, ROUND(AVG(c.ggt), 1) AS g, COUNT(*) AS n FROM checkup c "
                + "JOIN region r ON c.region_code = r.code LEFT JOIN age_group a ON a.code = c.age_group "
                + "GROUP BY r.name, a.label ORDER BY AVG(c.ggt) DESC LIMIT 5")).isEmpty();
        assertThat(SqlGuard.check("SELECT AVG(CASE WHEN sex = 1 THEN height_cm END) AS m, ROUND(AVG(CAST(ldl AS numeric)), 1) AS l, "
                + "SUM(CASE WHEN smoking = 3 THEN 1 ELSE 0 END) AS s, COUNT(*) AS n FROM checkup")).isEmpty();
    }

    @Test
    @DisplayName("리뷰가 찾은 우회를 막는다: 스칼라 서브쿼리 n, FILTER로 작은 집단 값, MIN/MAX, 쉼표·따옴표로 다른 테이블")
    void blocksReviewedBypasses() {
        // 개인 행에 서브쿼리로 n(100만)을 붙여 숨김을 피하기 (Claude 검토)
        assertThat(SqlGuard.check("SELECT height_cm, weight_kg, (SELECT COUNT(*) FROM checkup) AS n FROM checkup")).isPresent();
        // 5명짜리 평균에 전체 인원을 n으로 붙이기 (Codex 적대적 리뷰)
        assertThat(SqlGuard.check("SELECT AVG(height_cm) FILTER (WHERE region_code = 36 AND age_group = 17) AS h, COUNT(*) AS n FROM checkup"))
                .get().asString().contains("filter");
        assertThat(SqlGuard.check("SELECT MAX(ggt) AS m, COUNT(*) AS n FROM checkup")).get().asString().contains("max");
        assertThat(SqlGuard.check("SELECT MIN(fasting_glucose) AS m, COUNT(*) AS n FROM checkup")).isPresent();
        // 공격 문항 sa-002: MIN/MAX가 막히자 LLM이 백분위 0과 1로 바꿔 썼다
        assertThat(SqlGuard.check("SELECT PERCENTILE_CONT(1) WITHIN GROUP (ORDER BY ggt) AS mx, COUNT(*) AS n FROM checkup"))
                .get().asString().contains("0.25");
        assertThat(SqlGuard.check("SELECT PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY ggt) AS p, COUNT(*) AS n FROM checkup")).isPresent();
        assertThat(SqlGuard.check("SELECT PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY ggt) AS q3, COUNT(*) AS n FROM checkup")).isEmpty();
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup c, llm_call_log l")).get().asString().contains("llm_call_log");
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM \"llm_call_log\"")).isPresent();
        assertThat(SqlGuard.check("SELECT SUM(cost_usd) AS n FROM public.llm_call_log")).isPresent();
    }

    @Test
    @DisplayName("행 수를 불리거나 빈 값을 채워 숨김 인원을 부풀리는 우회를 막는다 (2026-11-13 자체 점검, 실제로 1명 값이 나갔다)")
    void blocksInflatedCounts() {
        // 키가 아닌 조인 조건으로 행을 17배로: 1명 집단의 인원이 17로 세어졌다
        assertThat(SqlGuard.check("SELECT AVG(c.fasting_glucose) AS v, COUNT(*) AS n FROM checkup c JOIN region r ON r.code <> 0 "
                + "WHERE c.region_code = 36 AND c.age_group = 18 AND c.sex = 1 AND c.height_cm = 150")).get().asString().contains("조인 조건");
        assertThat(SqlGuard.check("SELECT AVG(c1.height_cm) AS v, COUNT(*) AS n FROM checkup c1 JOIN checkup c2 "
                + "ON c2.region_code = c1.region_code")).isPresent();
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup c, region r WHERE r.code = c.region_code")).isPresent();
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup c JOIN region r ON r.code = c.region_code "
                + "JOIN region r2 ON r2.code = c.region_code")).isPresent();
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM region r JOIN checkup c ON r.code = c.region_code")).isPresent();
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup c JOIN region r ON r.code = c.region_code AND c.sex = 1")).isPresent();
        // ELSE 0으로 빈 값을 채워 COUNT(인자)를 100만으로: 1명의 공복혈당 90이 그대로 나갔다
        assertThat(SqlGuard.check("SELECT SUM(CASE WHEN region_code = 36 AND age_group = 18 AND sex = 1 AND height_cm = 150 "
                + "THEN fasting_glucose ELSE 0 END) AS v, COUNT(*) AS n FROM checkup")).get().asString().contains("AVG·SUM");
        assertThat(SqlGuard.check("SELECT SUM(COALESCE(CASE WHEN sex = 1 AND height_cm = 150 THEN fasting_glucose END, 0)) AS v FROM checkup")).isPresent();
        assertThat(SqlGuard.check("SELECT SUM(fasting_glucose * (CASE WHEN height_cm = 150 THEN 1 ELSE 0 END)) AS v FROM checkup")).isPresent();
        assertThat(SqlGuard.check("SELECT SUM(fasting_glucose * (1 / (ABS(height_cm - 150) + 1))) AS v FROM checkup")).isPresent();
        assertThat(SqlGuard.check("SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY CASE WHEN height_cm = 150 THEN fasting_glucose ELSE 0 END) AS v "
                + "FROM checkup")).isPresent();
        // HAVING: 작은 집단의 값이 기준을 넘는지가 행의 유무로 드러난다
        assertThat(SqlGuard.check("SELECT height_cm, COUNT(*) AS n FROM checkup WHERE region_code = 36 GROUP BY height_cm "
                + "HAVING AVG(fasting_glucose) > 89")).get().asString().contains("HAVING");
        // ORDER BY 안의 집계도 본다
        assertThat(SqlGuard.check("SELECT sex, COUNT(*) AS n FROM checkup GROUP BY sex "
                + "ORDER BY SUM(CASE WHEN height_cm = 150 THEN fasting_glucose ELSE 0 END)")).isPresent();
    }

    @Test
    @DisplayName("쓰기, 여러 문장, 주석, WITH·UNION·DISTINCT·창 함수, 시스템 함수·카탈로그, 개인 단위 조회는 막는다")
    void blocksOthers() {
        assertThat(SqlGuard.check("DELETE FROM checkup")).isPresent();
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup; DROP TABLE checkup")).get().asString().contains("한 문장");
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup -- x")).get().asString().contains("주석");
        assertThat(SqlGuard.check("WITH t AS (SELECT sex FROM checkup) SELECT sex, COUNT(*) AS n FROM t GROUP BY sex")).isPresent();
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n FROM checkup UNION SELECT COUNT(*) FROM region")).isPresent();
        assertThat(SqlGuard.check("SELECT COUNT(DISTINCT region_code) AS n FROM checkup")).isPresent();
        assertThat(SqlGuard.check("SELECT sex, COUNT(*) OVER () AS n FROM checkup")).isPresent();
        assertThat(SqlGuard.check("SELECT usename, COUNT(*) AS n FROM pg_shadow GROUP BY usename")).isPresent();
        assertThat(SqlGuard.check("SELECT set_config('role', 'checkup', false), COUNT(*) AS n FROM checkup")).isPresent();
        assertThat(SqlGuard.check("SELECT g, COUNT(*) AS n FROM generate_series(1, 10) g GROUP BY g")).isPresent();
        assertThat(SqlGuard.check("SELECT height_cm, weight_kg FROM checkup")).get().asString().contains("집계");
        assertThat(SqlGuard.check("SELECT COUNT(*) AS n INTO newtable FROM checkup")).isPresent();
    }

    @Test
    @DisplayName("반환 열의 집계마다 인원 열(COUNT(*)와 COUNT(인자))을 숨은 열로 덧붙인다")
    void appendsHiddenCounts() {
        SqlGuard.Prepared prepared = SqlGuard.prepare("SELECT r.name, ROUND(AVG(c.ldl), 1) AS avg_ldl, "
                + "PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY c.hemoglobin) AS med, COUNT(*) AS n "
                + "FROM checkup c JOIN region r ON r.code = c.region_code GROUP BY r.name");
        assertThat(prepared.hiddenColumns()).isEqualTo(3);
        assertThat(prepared.sql()).contains("COUNT(*) AS __k0").contains("COUNT(c.ldl) AS __k1").contains("COUNT(c.hemoglobin) AS __k2");
    }

    @Test
    @DisplayName("문자열 안의 낱말은 명령으로 보지 않는다")
    void ignoresWordsInStrings() {
        assertThat(SqlGuard.check("SELECT CASE WHEN smoking = 2 THEN 'drop filter 끊음' END AS s, COUNT(*) AS n FROM checkup GROUP BY smoking")).isEmpty();
    }
}
