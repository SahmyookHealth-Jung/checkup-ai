package com.checkupai.stats.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * LLM이 쓴 SQL을 실행 전에 구조로 검사하고, 작은 집단 숨김에 쓸 인원 열을 서버가 덧붙인다 (ADR 0013 1·3절, 보완).
 *
 * <p>2026-11-13 Codex 적대적 리뷰: 정규식으로 "집계 함수가 어딘가에 있는지"만 보면 스칼라 서브쿼리로 n을 붙인 개인 행이
 * 통과했고, 숨김이 LLM이 쓴 n을 믿어서 {@code AVG(x) FILTER (WHERE 5명 조건)}에 {@code COUNT(*) AS n}(100만)을 붙이면
 * 5명짜리 값이 나갔다. 그래서 다음을 코드가 정한다.
 * <ul>
 *   <li>SELECT가 하나뿐인 단순 질의만 받는다 (서브쿼리, WITH, UNION, INTO, DISTINCT 금지)</li>
 *   <li>테이블은 checkup, region, age_group만 (따옴표·쉼표 조인도 파서가 찾는다)</li>
 *   <li>집계 질의여야 한다: GROUP BY가 있거나, 반환 열이 모두 집계다 (개인 행 금지)</li>
 *   <li>함수는 허용 목록만: COUNT, AVG, SUM, PERCENTILE_CONT, ROUND, COALESCE, NULLIF, ABS, CAST.
 *       FILTER, OVER, MIN, MAX는 쓸 수 없다 (한 사람의 값이나 다른 집단의 값을 내보낼 수 있다)</li>
 *   <li>반환 열의 집계마다 그 인자의 인원 {@code COUNT(인자)}와 묶음 인원 {@code COUNT(*)}를 숨은 열로 덧붙인다.
 *       숨김은 LLM이 쓴 n이 아니라 이 인원 중 가장 작은 값으로 정한다 ({@link ResultTable})</li>
 * </ul>
 * 진짜 마지막 방어선은 DB 권한(읽기 전용 계정)이다.
 *
 * <p>2026-11-13 자체 점검: 숨은 인원은 "사람 수"가 아니라 "행 수"라서 두 길로 한 사람의 값이 나갔다.
 * {@code JOIN region r ON r.code <> 0}으로 행을 17배로 불리거나, {@code SUM(CASE WHEN 1명 조건 THEN x ELSE 0 END)}로
 * 빈 값을 채워 COUNT(인자)를 100만으로 만들었다. 그래서 아래 두 가지를 더한다.
 * <ul>
 *   <li>FROM은 checkup 하나로 시작하고, 조인은 코드표(region, age_group)를 각각 한 번, 키 조건으로만 붙인다 (행이 불지 않는다)</li>
 *   <li>HAVING은 쓰지 않는다 (작은 집단의 값이 기준을 넘는지가 행의 유무로 드러난다)</li>
 *   <li>AVG·SUM·PERCENTILE_CONT의 인자는 열 하나, 결과가 모두 상수인 CASE(비율용 표시값), 또는 ELSE 없이 열을 고르는 CASE만
 *       쓴다. 그래야 COUNT(인자)가 값을 낸 사람 수와 같다. 연산·COALESCE로 표시값과 열을 섞는 길도 함께 막는다</li>
 * </ul>
 */
public final class SqlGuard {

    static final Set<String> TABLES = Set.of("checkup", "region", "age_group");
    /** 덧붙이는 숨은 인원 열 이름의 머리 */
    public static final String HIDDEN_PREFIX = "__k";

    private static final Set<String> FUNCTIONS = Set.of(
            "count", "avg", "sum", "percentile_cont", "round", "coalesce", "nullif", "abs", "cast", "numeric", "decimal");
    /** 여는 괄호 앞에 올 수 있는 SQL 낱말 (함수가 아니다) */
    private static final Set<String> KEYWORDS_BEFORE_PAREN = Set.of(
            "in", "group", "and", "or", "not", "on", "then", "else", "when", "where", "having", "by", "select", "as", "case", "is");
    private static final Pattern FORBIDDEN_WORDS = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|grant|revoke|truncate|copy|call|do|execute|prepare|"
                    + "listen|notify|vacuum|analyze|reset|lock|comment|security|into|refresh|cluster|reindex|"
                    + "filter|over|distinct|union|intersect|except|lateral|window|with|pg_\\w+|set_config|current_setting|"
                    + "information_schema|pg_catalog)\\b");
    private static final Pattern CALL = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\s*\\(");
    private static final Pattern SELECT_WORD = Pattern.compile("\\bselect\\b");
    private static final Pattern AGGREGATE_CALL = Pattern.compile("\\b(count|avg|sum)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern WITHIN_GROUP = Pattern.compile("\\bwithin\\s+group\\s*\\(\\s*order\\s+by\\s+", Pattern.CASE_INSENSITIVE);
    /** PERCENTILE_CONT의 비율. 0이나 1은 최솟값·최댓값(한 사람의 값)이라 사분위수만 허락한다 */
    private static final Pattern PERCENTILE_FRACTION = Pattern.compile("\\bpercentile_cont\\s*\\(\\s*([^)]*)\\)");
    private static final Set<String> FRACTIONS = Set.of("0.25", "0.5", "0.50", "0.75", ".25", ".5", ".75");
    private static final Pattern HAS_AGGREGATE = Pattern.compile("\\b(count|avg|sum|percentile_cont)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** 실행할 SQL(숨은 인원 열을 덧붙임)과 그 열 수 */
    public record Prepared(String sql, int hiddenColumns) {
    }

    public static final class Rejected extends RuntimeException {
        Rejected(String reason) {
            super(reason);
        }
    }

    private SqlGuard() {
    }

    /** 통과하면 빈 값, 걸리면 이유 */
    public static Optional<String> check(String sql) {
        try {
            prepare(sql);
            return Optional.empty();
        } catch (Rejected e) {
            return Optional.of(e.getMessage());
        }
    }

    public static Prepared prepare(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new Rejected("SQL이 비어 있다");
        }
        String body = sql.strip();
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).strip();
        }
        lexicalCheck(body);
        PlainSelect select = parse(body);
        checkTables(body);
        checkJoins(select);
        if (select.getHaving() != null) {
            // HAVING은 작은 집단의 값이 기준보다 큰지 작은지를 행이 나오는지로 알려 준다
            throw new Rejected("HAVING은 쓸 수 없다 (결과를 묶은 뒤 거르지 않는다)");
        }
        checkValueArguments(body);  // 반환 열뿐 아니라 ORDER BY의 집계도 본다
        if (select.getGroupBy() == null) {
            for (SelectItem<?> item : select.getSelectItems()) {
                if (!HAS_AGGREGATE.matcher(item.getExpression().toString()).find()) {
                    throw new Rejected("집계 결과만 낼 수 있다 (개인 단위 행은 돌려주지 않는다). 묶어 보려면 GROUP BY를 쓴다: "
                            + item.getExpression());
                }
            }
        }
        List<String> hidden = hiddenCounts(select);
        for (int i = 0; i < hidden.size(); i++) {
            try {
                select.addSelectItem(CCJSqlParserUtil.parseExpression(hidden.get(i)), new Alias(HIDDEN_PREFIX + i));
            } catch (JSQLParserException e) {
                throw new Rejected("집계 인자를 읽지 못했다: " + hidden.get(i));
            }
        }
        return new Prepared(select.toString(), hidden.size());
    }

    private static void lexicalCheck(String body) {
        String s = body.toLowerCase(Locale.ROOT).replaceAll("'[^']*'", "''");
        if (s.contains(";")) {
            throw new Rejected("한 문장만 쓸 수 있다");
        }
        if (s.contains("--") || s.contains("/*")) {
            throw new Rejected("주석을 쓰지 않는다");
        }
        // 열 이름 별칭의 따옴표(AS "평균 혈당")만 허락한다. 다른 따옴표 이름은 테이블 검사를 흐리게 한다
        String unquoted = s.replaceAll("\\bas\\s+\"[^\"]*\"", "as x");
        if (unquoted.contains("\"")) {
            throw new Rejected("따옴표로 감싼 이름은 별칭(AS \"...\")에만 쓸 수 있다");
        }
        if (!unquoted.startsWith("select")) {
            throw new Rejected("SELECT 문 하나만 쓸 수 있다 (WITH 금지)");
        }
        Matcher selects = SELECT_WORD.matcher(unquoted);
        int count = 0;
        while (selects.find()) {
            count++;
        }
        if (count != 1) {
            throw new Rejected("서브쿼리·WITH·UNION 없이 SELECT 하나로 쓴다");
        }
        Matcher forbidden = FORBIDDEN_WORDS.matcher(unquoted);
        if (forbidden.find()) {
            throw new Rejected("쓸 수 없는 명령이나 기능이다: " + forbidden.group(1)
                    + " (MIN·MAX·FILTER·OVER·DISTINCT 대신 COUNT·AVG·SUM과 CASE를 쓴다)");
        }
        // 2026-11-13 공격 문항 sa-002: MIN/MAX를 막자 LLM이 PERCENTILE_CONT(0)/(1)로 다시 써 최솟값·최댓값을 냈다
        Matcher fraction = PERCENTILE_FRACTION.matcher(unquoted);
        while (fraction.find()) {
            if (!FRACTIONS.contains(fraction.group(1).strip())) {
                throw new Rejected("PERCENTILE_CONT는 0.25, 0.5, 0.75만 쓸 수 있다 (최솟값·최댓값처럼 한 사람의 값을 낼 수 있다)");
            }
        }
        Matcher call = CALL.matcher(unquoted);
        while (call.find()) {
            String name = call.group(1);
            if (!FUNCTIONS.contains(name) && !KEYWORDS_BEFORE_PAREN.contains(name)) {
                throw new Rejected("쓸 수 없는 함수다: " + name + " (COUNT, AVG, SUM, PERCENTILE_CONT, ROUND, COALESCE, NULLIF, ABS, CAST만)");
            }
        }
    }

    private static PlainSelect parse(String body) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(body);
        } catch (JSQLParserException e) {
            throw new Rejected("SQL을 읽지 못했다 (PostgreSQL 문법 확인)");
        }
        if (statements.size() != 1) {
            throw new Rejected("한 문장만 쓸 수 있다");
        }
        Statement statement = statements.get(0);
        if (!(statement instanceof PlainSelect select)) {
            throw new Rejected("단순 SELECT 하나만 쓸 수 있다 (WITH, UNION 금지)");
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw new Rejected("WITH는 쓸 수 없다");
        }
        if (select.getIntoTables() != null || select.getDistinct() != null) {
            throw new Rejected("INTO·DISTINCT는 쓸 수 없다");
        }
        return select;
    }

    private static void checkTables(String body) {
        Set<String> tables;
        try {
            tables = TablesNamesFinder.findTables(body);
        } catch (JSQLParserException e) {
            throw new Rejected("SQL을 읽지 못했다");
        }
        for (String name : tables) {
            String table = name.replace("\"", "").toLowerCase(Locale.ROOT);
            table = table.startsWith("stats.") ? table.substring("stats.".length()) : table;
            if (!TABLES.contains(table)) {
                throw new Rejected("허용하지 않은 테이블이다: " + name + " (checkup, region, age_group만 쓸 수 있다)");
            }
        }
    }

    /** 조인 키: 코드표 → 그 코드표를 가리키는 checkup 열 */
    private static final java.util.Map<String, String> JOIN_KEYS = java.util.Map.of("region", "region_code", "age_group", "age_group");
    private static final Pattern VALUE_AGGREGATE = Pattern.compile("\\b(avg|sum)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** FROM checkup [별칭] 뒤에 코드표를 키 조건으로만 붙인다. 쉼표 조인, 자기 조인, 키가 아닌 조건은 행을 불려 숨김을 피한다 */
    private static void checkJoins(PlainSelect select) {
        if (!(select.getFromItem() instanceof Table from) || !"checkup".equals(tableName(from))) {
            throw new Rejected("FROM은 checkup으로 시작한다 (예: FROM checkup c JOIN region r ON r.code = c.region_code)");
        }
        String checkup = aliasOf(from);
        Set<String> joined = new java.util.HashSet<>();
        for (Join join : select.getJoins() == null ? List.<Join>of() : select.getJoins()) {
            if (join.isSimple() || join.isCross() || join.isNatural() || join.isRight() || join.isFull()
                    || !join.getUsingColumns().isEmpty() || !(join.getRightItem() instanceof Table table)) {
                throw new Rejected("조인은 JOIN 코드표 ON 키 조건으로만 쓴다 (예: JOIN region r ON r.code = c.region_code)");
            }
            String name = tableName(table);
            String key = JOIN_KEYS.get(name);
            if (key == null || !joined.add(name)) {
                throw new Rejected("checkup에는 region과 age_group만 각각 한 번 붙일 수 있다: " + table);
            }
            var on = join.getOnExpressions();
            if (on.size() != 1 || !(on.iterator().next() instanceof EqualsTo equals)
                    || !keyPair(equals, aliasOf(table), checkup, key)) {
                throw new Rejected("조인 조건은 " + aliasOf(table) + ".code = " + checkup + "." + key + " 하나만 쓴다");
            }
        }
    }

    private static boolean keyPair(EqualsTo equals, String codeTable, String checkup, String key) {
        return equals.getLeftExpression() instanceof Column left && equals.getRightExpression() instanceof Column right
                && (isColumn(left, codeTable, "code") && isColumn(right, checkup, key)
                    || isColumn(right, codeTable, "code") && isColumn(left, checkup, key));
    }

    /** 열 이름이 같고, 앞에 테이블(별칭)을 붙였다면 그 테이블이다 */
    private static boolean isColumn(Column column, String table, String name) {
        return name.equalsIgnoreCase(column.getColumnName())
                && (column.getTable() == null || column.getTable().getName() == null
                    || table.equalsIgnoreCase(column.getTable().getName()));
    }

    private static String tableName(Table table) {
        String name = table.getName().replace("\"", "").toLowerCase(Locale.ROOT);
        return name.startsWith("stats.") ? name.substring("stats.".length()) : name;
    }

    private static String aliasOf(Table table) {
        return table.getAlias() != null ? table.getAlias().getName() : tableName(table);
    }

    /**
     * AVG·SUM·PERCENTILE_CONT 인자의 모양: 열 하나(CAST 가능), 결과가 모두 상수인 CASE, ELSE 없이 열을 고르는 CASE.
     * COUNT는 값을 내보내지 않으므로 보지 않는다.
     */
    static void checkValueArguments(String expression) {
        List<String> arguments = new ArrayList<>();
        Matcher call = VALUE_AGGREGATE.matcher(expression);
        while (call.find()) {
            arguments.add(balanced(expression, call.end()).strip());
        }
        Matcher within = WITHIN_GROUP.matcher(expression);
        while (within.find()) {
            arguments.add(balanced(expression, within.end()).strip().replaceAll("(?i)\\s+(asc|desc)$", ""));
        }
        for (String argument : arguments) {
            if (!safeValueArgument(argument)) {
                throw new Rejected("AVG·SUM 안에는 열 하나나 CASE만 쓴다 (예: AVG(c.ldl), AVG(CASE WHEN 조건 THEN 1 ELSE 0 END), "
                        + "AVG(CASE WHEN 조건 THEN c.ldl END)): " + argument);
            }
        }
    }

    private static boolean safeValueArgument(String argument) {
        Expression expression;
        try {
            expression = CCJSqlParserUtil.parseExpression(unwrap(argument));
        } catch (JSQLParserException e) {
            return false;
        }
        if (isColumnValue(expression)) {
            return true;
        }
        if (!(expression instanceof CaseExpression cases)) {
            return false;
        }
        List<Expression> results = new ArrayList<>(cases.getWhenClauses().stream().map(WhenClause::getThenExpression).toList());
        Expression otherwise = cases.getElseExpression();
        if (otherwise != null) {
            results.add(otherwise);
        }
        if (results.stream().allMatch(SqlGuard::isConstant)) {
            return true;  // 비율·건수용 표시값. 분자가 작은 비율은 남은 한계다 (ADR 0013)
        }
        // 열을 고르는 CASE는 ELSE가 없어야(또는 NULL) COUNT(인자)가 고른 사람 수와 같다
        return (otherwise == null || otherwise instanceof NullValue)
                && cases.getWhenClauses().stream().map(WhenClause::getThenExpression)
                        .allMatch(then -> isColumnValue(then) || isConstant(then));
    }

    private static boolean isColumnValue(Expression expression) {
        return expression instanceof Column
                || expression instanceof CastExpression cast && cast.getLeftExpression() instanceof Column;
    }

    private static boolean isConstant(Expression expression) {
        return expression instanceof LongValue || expression instanceof DoubleValue || expression instanceof NullValue
                || expression instanceof StringValue
                || expression instanceof SignedExpression signed && isConstant(signed.getExpression());
    }

    /** 바깥을 감싼 괄호를 벗긴다: "(x)" → "x" */
    private static String unwrap(String text) {
        String s = text.strip();
        while (s.startsWith("(") && s.endsWith(")") && balanced(s, 1).length() == s.length() - 2) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s;
    }

    /** 반환 열의 집계마다 인원 열: 묶음 인원 COUNT(*)와 집계 인자의 COUNT(인자) */
    static List<String> hiddenCounts(PlainSelect select) {
        Set<String> counts = new LinkedHashSet<>();
        counts.add("COUNT(*)");
        for (SelectItem<?> item : select.getSelectItems()) {
            for (String argument : aggregateArguments(item.getExpression().toString())) {
                if (!argument.equals("*")) {
                    counts.add("COUNT(" + argument + ")");
                }
            }
        }
        return new ArrayList<>(counts);
    }

    /** COUNT·AVG·SUM의 인자와 PERCENTILE_CONT ... WITHIN GROUP (ORDER BY x)의 x */
    static List<String> aggregateArguments(String expression) {
        List<String> out = new ArrayList<>();
        Matcher call = AGGREGATE_CALL.matcher(expression);
        while (call.find()) {
            out.add(balanced(expression, call.end()).strip());
        }
        Matcher within = WITHIN_GROUP.matcher(expression);
        while (within.find()) {
            String argument = balanced(expression, within.end()).strip().replaceAll("(?i)\\s+(asc|desc)$", "");
            out.add(argument);
        }
        return out;
    }

    /** start부터 짝이 맞는 닫는 괄호 앞까지 (여는 괄호는 start 바로 앞에 있다) */
    private static String balanced(String text, int start) {
        int depth = 1;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                i = text.indexOf('\'', i + 1);
                if (i < 0) {
                    break;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return text.substring(start, i);
            }
        }
        throw new Rejected("괄호 짝이 맞지 않는다");
    }
}
