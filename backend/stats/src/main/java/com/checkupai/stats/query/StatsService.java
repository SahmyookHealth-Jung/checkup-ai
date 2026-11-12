package com.checkupai.stats.query;

import com.checkupai.core.llm.LlmCallContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 관리자 통계 질문 → SQL → 실행 → 작은 집단 숨김 (ADR 0013).
 * LLM 1회(검사·실행 실패 시 이유를 붙여 1회 더). 답하지 않음(NO_DATA, REFUSE)은 SQL을 실행하지 않는다.
 * 실행은 읽기 전용 계정으로만 하고, 결과에 n 컬럼이 없으면 실패로 보고 다시 쓰게 한다.
 */
@Service
public class StatsService {

    public static final String PROMPT_VERSION = "stats-sql-v1";
    static final String FEATURE = "stats.sql";
    static final int MAX_ATTEMPTS = 2;

    public enum Decision { ANSWER, NO_DATA, REFUSE, FAILED }

    /**
     * @param errors 시도마다 걸린 이유 (검사, 실행 오류, n 컬럼 없음)
     * @param llmDecision 마지막 시도에서 LLM이 낸 처리
     */
    public record StatsResult(String question, Decision decision, String llmDecision, String sql, String reason,
                              ResultTable table, List<String> errors, Metrics metrics) {
    }

    public record Metrics(String promptVersion, int attempts, long llmMs, long dbMs, long totalMs) {
    }

    private final ChatClient chatClient;
    private final StatsDatabase database;
    private final StatsProperties properties;
    private final String systemPrompt;
    private volatile String schema;

    public StatsService(ChatClient.Builder chatClientBuilder, StatsDatabase database, StatsProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.database = database;
        this.properties = properties;
        try {
            this.systemPrompt = new ClassPathResource("prompts/" + PROMPT_VERSION + ".st").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public StatsResult ask(String question) {
        long started = System.nanoTime();
        List<String> errors = new ArrayList<>();
        long llmMs = 0;
        long dbMs = 0;
        SqlDraft draft = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long t = System.nanoTime();
            draft = generate(question, errors.isEmpty() ? null : errors.getLast(), draft);
            llmMs += (System.nanoTime() - t) / 1_000_000;
            String decision = draft == null || draft.decision() == null ? "" : draft.decision().strip().toUpperCase();
            if (!decision.equals("ANSWER")) {
                Decision d = decision.equals("NO_DATA") ? Decision.NO_DATA : Decision.REFUSE;
                return result(question, d, draft, null, errors, attempt, llmMs, dbMs, started);
            }
            Optional<String> blocked = SqlGuard.check(draft.sql());
            if (blocked.isPresent()) {
                errors.add("검사: " + blocked.get());
                continue;
            }
            t = System.nanoTime();
            try {
                StatsDatabase.Rows rows = database.query(draft.sql());
                dbMs += (System.nanoTime() - t) / 1_000_000;
                if (ResultTable.countIndex(rows.columns()) < 0) {
                    errors.add("결과: 행마다 사람 수를 n 컬럼으로 내야 한다");
                    continue;
                }
                ResultTable table = ResultTable.suppress(rows.columns(), rows.rows(), properties.minGroupSize(), rows.truncated());
                return result(question, Decision.ANSWER, draft, table, errors, attempt, llmMs, dbMs, started);
            } catch (DataAccessException e) {
                dbMs += (System.nanoTime() - t) / 1_000_000;
                errors.add("실행: " + rootMessage(e));
            }
        }
        return result(question, Decision.FAILED, draft, null, errors, MAX_ATTEMPTS, llmMs, dbMs, started);
    }

    private StatsResult result(String question, Decision decision, SqlDraft draft, ResultTable table, List<String> errors,
                               int attempts, long llmMs, long dbMs, long started) {
        return new StatsResult(question, decision, draft == null ? null : draft.decision(), draft == null ? null : draft.sql(),
                draft == null ? null : draft.reason(), table, List.copyOf(errors),
                new Metrics(PROMPT_VERSION, attempts, llmMs, dbMs, (System.nanoTime() - started) / 1_000_000));
    }

    private SqlDraft generate(String question, String lastError, SqlDraft previous) {
        String user = lastError == null ? "<질문>" + question + "</질문>"
                : "<질문>" + question + "</질문>\n<앞의 SQL>" + (previous == null ? "" : previous.sql()) + "</앞의 SQL>\n<문제>"
                + lastError + "</문제>\n문제를 고친 SQL을 다시 쓴다.";
        return chatClient.prompt()
                .system(system -> system.text(systemPrompt).param("schema", schema()))
                .user(user)
                .options(ChatOptions.builder().maxTokens(properties.maxTokens()))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .advisors(advisor -> advisor.param(LlmCallContext.FEATURE, FEATURE))
                .call()
                .entity(new BeanOutputConverter<>(SqlDraft.class));
    }

    private String schema() {
        if (schema == null) {
            schema = database.describeSchema();
        }
        return schema;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
