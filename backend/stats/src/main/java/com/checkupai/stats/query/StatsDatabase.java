package com.checkupai.stats.query;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 읽기 전용 계정(stats_reader)으로만 여는 연결 (ADR 0013 1절).
 * DataSource를 빈으로 내놓지 않는다: 앱의 기본 DataSource 자동 구성을 바꾸지 않고, 다른 코드가 이 연결을 쓰지 않게 한다.
 */
public class StatsDatabase {

    public record Rows(List<String> columns, List<List<Object>> rows, boolean truncated) {
    }

    private final JdbcTemplate jdbc;
    private final int maxRows;

    public StatsDatabase(StatsProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(properties.url(), properties.username(), properties.password());
        this.jdbc = new JdbcTemplate(dataSource);
        this.maxRows = properties.maxRows();
    }

    /** SQL을 감싸 행 상한을 걸고 실행한다. 시간 제한과 읽기 전용은 계정 설정이 건다 */
    public Rows query(String sql) {
        String body = sql.strip();
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1);
        }
        String wrapped = "SELECT * FROM (" + body + ") AS q LIMIT " + (maxRows + 1);
        return jdbc.query(wrapped, rs -> {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnLabel(i));
            }
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(columns.size());
                for (int i = 1; i <= columns.size(); i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
            boolean truncated = rows.size() > maxRows;
            return new Rows(columns, truncated ? rows.subList(0, maxRows) : rows, truncated);
        });
    }

    private volatile Set<String> codeLabels;

    /** 코드표의 이름(시도 이름, 연령대 이름). 숨긴 행에서도 보여 줄 수 있는 글자다 */
    public Set<String> codeLabels() {
        if (codeLabels == null) {
            Set<String> labels = new HashSet<>(jdbc.queryForList("SELECT name FROM region", String.class));
            labels.addAll(jdbc.queryForList("SELECT label FROM age_group", String.class));
            codeLabels = Set.copyOf(labels);
        }
        return codeLabels;
    }

    /** LLM에 주는 스키마 설명: 컬럼 설명(COMMENT)과 코드표 값 */
    public String describeSchema() {
        StringBuilder out = new StringBuilder();
        jdbc.query("""
                SELECT c.relname AS table_name, obj_description(c.oid, 'pg_class') AS table_comment,
                       a.attname AS column_name, format_type(a.atttypid, a.atttypmod) AS type,
                       col_description(c.oid, a.attnum) AS column_comment
                FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                WHERE ns.nspname = 'stats' AND c.relkind = 'r'
                ORDER BY c.relname, a.attnum
                """, rs -> {
            String table = rs.getString("table_name");
            if (!out.toString().contains("\n테이블 " + table + " ")) {
                out.append("\n테이블 ").append(table).append(" -- ").append(rs.getString("table_comment")).append('\n');
            }
            out.append("  ").append(rs.getString("column_name")).append(' ').append(rs.getString("type"))
                    .append(" -- ").append(rs.getString("column_comment")).append('\n');
        });
        out.append("\n시도 코드: ");
        out.append(String.join(", ", jdbc.queryForList("SELECT code || ' ' || name FROM region ORDER BY code", String.class)));
        out.append("\n연령대 코드: ");
        out.append(String.join(", ", jdbc.queryForList("SELECT code || ' ' || label FROM age_group ORDER BY code", String.class)));
        return out.toString().strip();
    }
}
