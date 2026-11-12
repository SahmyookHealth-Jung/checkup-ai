package com.checkupai.stats.query;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM이 쓴 SQL을 실행 전에 거른다 (ADR 0013 1절). 진짜 방어선은 DB 권한(읽기 전용 계정)이고, 여기는 그 앞의 겹이다.
 * 걸리면 이유를 돌려주고, 서비스는 그 이유를 붙여 한 번만 다시 쓰게 한다.
 */
public final class SqlGuard {

    static final Set<String> TABLES = Set.of("checkup", "region", "age_group");

    private static final Pattern WRITE_OR_ADMIN = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|grant|revoke|truncate|copy|call|do|execute|prepare|"
                    + "listen|notify|vacuum|analyze|reset|lock|comment|security|into|refresh|cluster|reindex)\\b");
    /** 설정을 바꾸거나 서버 밖과 통하는 함수, 카탈로그 접근 */
    private static final Pattern DANGEROUS = Pattern.compile(
            "\\b(set_config|current_setting|pg_\\w+|dblink\\w*|lo_\\w+|query_to_xml\\w*|table_to_xml\\w*|"
                    + "information_schema|pg_catalog|public)\\b");
    private static final Pattern RELATION = Pattern.compile("\\b(?:from|join)\\s+([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)?)");
    private static final Pattern CTE_NAME = Pattern.compile("(?:\\bwith|,)\\s*([a-z_][a-z0-9_]*)\\s+as\\s*\\(");
    private static final Pattern AGGREGATE = Pattern.compile("\\b(count|avg|sum|min|max|percentile_cont|percentile_disc|stddev\\w*|variance)\\s*\\(");

    private SqlGuard() {
    }

    /** 통과하면 빈 값, 걸리면 이유 */
    public static Optional<String> check(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.of("SQL이 비어 있다");
        }
        String s = sql.strip().toLowerCase(Locale.ROOT);
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (s.contains(";")) {
            return Optional.of("한 문장만 쓸 수 있다");
        }
        if (s.contains("--") || s.contains("/*")) {
            return Optional.of("주석을 쓰지 않는다");
        }
        if (!(s.startsWith("select") || s.startsWith("with"))) {
            return Optional.of("SELECT 문만 쓸 수 있다");
        }
        String withoutStrings = s.replaceAll("'[^']*'", "''");
        Matcher write = WRITE_OR_ADMIN.matcher(withoutStrings);
        if (write.find()) {
            return Optional.of("쓰기·관리 명령은 쓸 수 없다: " + write.group(1));
        }
        Matcher dangerous = DANGEROUS.matcher(withoutStrings);
        if (dangerous.find()) {
            return Optional.of("쓸 수 없는 함수나 스키마다: " + dangerous.group(1));
        }
        Set<String> ctes = new java.util.HashSet<>();
        Matcher cte = CTE_NAME.matcher(withoutStrings);
        while (cte.find()) {
            ctes.add(cte.group(1));
        }
        Matcher relation = RELATION.matcher(withoutStrings);
        while (relation.find()) {
            String name = relation.group(1);
            String table = name.startsWith("stats.") ? name.substring("stats.".length()) : name;
            if (!TABLES.contains(table) && !ctes.contains(table)) {
                return Optional.of("허용하지 않은 테이블이다: " + name + " (checkup, region, age_group만 쓸 수 있다)");
            }
        }
        if (!AGGREGATE.matcher(withoutStrings).find()) {
            return Optional.of("집계 결과만 낼 수 있다 (개인 단위 행은 돌려주지 않는다). COUNT·AVG 같은 집계를 쓴다");
        }
        return Optional.empty();
    }
}
