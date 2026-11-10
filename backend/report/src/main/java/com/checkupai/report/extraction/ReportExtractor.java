package com.checkupai.report.extraction;

import com.checkupai.core.llm.LlmCallContext;
import com.checkupai.report.extraction.ExtractionProperties.Pipeline;
import com.checkupai.report.extraction.ExtractionValidator.Issue;
import com.checkupai.report.extraction.ExtractionValidator.IssueType;
import com.checkupai.report.extraction.ExtractionValidator.ValidationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 결과지 텍스트에서 검사 항목을 추출한다 (ADR 0003).
 *
 * <ul>
 *   <li>baseline: 구조화 출력(JSON 스키마 강제) 한 번. 기준선 재현용</li>
 *   <li>validated: 기준선 + 원문 대조 검증 + 누락 의심 조각만 한 번 더 요청</li>
 * </ul>
 * 모델 공급자는 모른다. app 모듈 설정(spring.ai.model.chat)이 정한다.
 *
 * <p>v1 → v2 변경 (2026-11-08 평가에서 재요청이 35분 폭주한 문제 대응):
 * 재요청에는 결과지 전문 대신 표 머리 줄과 누락 조각만 보낸다. 호출마다 출력 토큰 상한을 둔다.
 * 재요청이 실패해도 1차 결과는 반환한다.
 *
 * <p>v2.2: 출력이 상한에서 잘려 JSON이 깨지면 완성된 항목까지 살린다(2026-11-09 sum-008이 이 때문에 실패).
 * 살린 항목은 검증을 거치고, 빠진 부분은 누락 조각 재요청이 채운다.
 *
 * <p>v2.3: 누락 조각이 많으면 몇 개씩 나눠 재요청한다. 한 묶음이 실패해도 나머지는 계속한다.
 */
@Service
public class ReportExtractor {

    private static final Logger log = LoggerFactory.getLogger(ReportExtractor.class);

    static final String PROMPT_VERSION = "extraction-v1";
    /** 호출 기록에 남는 기능 이름 (core LlmCallLoggingAdvisor) */
    private static final String FEATURE_FIRST_PASS = "report.extraction.first-pass";
    private static final String FEATURE_RETRY = "report.extraction.retry";

    private static final String USER_TEMPLATE = """
            다음 검진 결과지에서 검사 결과를 추출하라.

            <결과지>
            {report}
            </결과지>
            """;

    private static final String RETRY_USER_TEMPLATE = """
            <표 머리>
            {header}
            </표 머리>

            <추출할 조각>
            {segments}
            </추출할 조각>
            """;

    private final ChatClient chatClient;
    private final ExtractionValidator validator;
    private final ExtractionProperties properties;
    private final String defaultModel;
    private final String systemPrompt;
    private final String retryPrompt;

    public ReportExtractor(ChatClient.Builder chatClientBuilder, ChatModel chatModel,
                           ExtractionValidator validator, ExtractionProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.validator = validator;
        this.properties = properties;
        this.defaultModel = chatModel.getOptions().getModel();
        this.systemPrompt = loadPrompt("prompts/report-extraction-v1.st");
        this.retryPrompt = loadPrompt("prompts/report-extraction-retry-v2.st");
    }

    public ExtractionInfo info() {
        return new ExtractionInfo(defaultModel, PROMPT_VERSION, properties.pipelineLabel(), properties.settingsLabel());
    }

    public ExtractionResult extract(String reportText) {
        return extract(reportText, false);
    }

    public ExtractionResult extract(String reportText, boolean withDiagnostics) {
        long started = System.nanoTime();
        List<ChatResponse> responses = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();

        CheckupReport firstPass = call(FEATURE_FIRST_PASS, systemPrompt, USER_TEMPLATE, Map.of("report", reportText),
                properties.firstPassMaxTokens(), CheckupReport.class, responses, issues);
        long firstPassMs = (System.nanoTime() - started) / 1_000_000;  // 모델 비교: "1차만" 조건의 지연
        if (firstPass == null) {
            // API 모델이 빈 응답이나 거절을 돌려주면 변환 결과가 null일 수 있다. NPE 대신 원인을 밝혀 실패시킨다
            throw new IllegalStateException("1차 추출 응답을 결과지 형식으로 읽지 못했다 (빈 응답 또는 거절)");
        }
        boolean firstPassTruncated = issues.stream().anyMatch(issue -> issue.type() == IssueType.TRUNCATED_OUTPUT);
        if (properties.pipeline() == Pipeline.BASELINE) {
            var diagnostics = withDiagnostics
                    ? new ExtractionResult.Diagnostics(firstPass.items(), List.of(), null, null, null) : null;
            return new ExtractionResult(firstPass, metrics(started, firstPassMs, responses, 0, List.of()), diagnostics,
                    List.of());
        }

        ValidationResult validation = validator.validate(firstPass, reportText);
        issues.addAll(validation.issues());
        List<ReviewItem> needsReview = new ArrayList<>(validation.needsReview());
        CheckupReport report = validation.report();
        List<String> segments = validation.uncoveredSegments();
        List<String> header = SourceText.headerLines(reportText);
        List<ReportItem> retryItems = null;
        String retryError = null;

        // 재요청을 꺼도 1차 출력이 실패한 것으로 보이면 복구하려고 재요청한다.
        // - 잘림: 출력 상한에서 끊겼다 (ADR 0004 4절)
        // - 일찍 멈춤: 누락 의심 조각이 뽑은 항목보다 많다. 2026-11-10 Haiku 5.5가 63회 중 2회, 22개 중 1개만 내고
        //   멈췄다(같은 결과지를 다시 부르면 정상). 정상 보고서는 항목 20여 개, 누락 의심 조각 2~5개라 걸리지 않는다
        boolean firstPassIncomplete = segments.size() > report.items().size();
        if (firstPassIncomplete) {
            issues.add(new Issue(IssueType.INCOMPLETE_FIRST_PASS, null,
                    "누락 의심 조각 " + segments.size() + "개가 뽑은 항목 " + report.items().size() + "개보다 많다"));
        }
        boolean retry = !segments.isEmpty() && (properties.retryEnabled() || firstPassTruncated || firstPassIncomplete);
        if (retry) {
            String headerText = header.isEmpty() ? "(없음)" : String.join("\n", header);
            List<String> errors = new ArrayList<>();
            retryItems = new ArrayList<>();
            int batchSize = Math.max(1, properties.retryBatchSize());
            for (int from = 0; from < segments.size(); from += batchSize) {
                List<String> batch = segments.subList(from, Math.min(from + batchSize, segments.size()));
                try {
                    ItemList retried = call(FEATURE_RETRY, retryPrompt, RETRY_USER_TEMPLATE,
                            Map.of("header", headerText, "segments", String.join("\n", batch)),
                            properties.retryMaxTokens(), ItemList.class, responses, issues);
                    List<ReportItem> items = retried == null || retried.items() == null ? List.of() : retried.items();
                    retryItems.addAll(items);
                    ValidationResult merged = validator.validateAdditional(report, items, reportText);
                    issues.addAll(merged.issues());
                    merged.needsReview().stream()  // 재요청이 같은 범위 밖 값을 다시 뽑아도 한 번만 남긴다
                            .filter(review -> needsReview.stream().noneMatch(seen -> seen.code() == review.code()
                                    && SourceText.squash(seen.value()).equals(SourceText.squash(review.value()))))
                            .forEach(needsReview::add);
                    report = merged.report();
                } catch (RuntimeException e) {
                    // 재요청 실패(시간 초과, 살릴 항목 없이 잘린 출력 등)는 치명적이지 않다. 그 묶음만 건너뛴다
                    errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    log.warn("누락 조각 재요청 실패, 이 묶음은 건너뛴다: {}", errors.getLast());
                }
            }
            retryError = errors.isEmpty() ? null : String.join(" | ", errors);
        }

        var diagnostics = withDiagnostics
                ? new ExtractionResult.Diagnostics(firstPass.items(), issues,
                        segments.isEmpty() ? null : concat(header, segments), retryItems, retryError)
                : null;
        return new ExtractionResult(report, metrics(started, firstPassMs, responses, retry ? segments.size() : 0, issues),
                diagnostics, List.copyOf(needsReview));
    }

    private <T> T call(String feature, String system, String userTemplate, Map<String, Object> params, int maxTokens,
                       Class<T> type, List<ChatResponse> responses, List<Issue> issues) {
        // 기준선은 재현용이므로 잘린 출력을 살리지 않는다
        StructuredOutputConverter<T> converter = properties.pipeline() == Pipeline.BASELINE
                ? new BeanOutputConverter<>(type) : new SalvagingOutputConverter<>(type);
        ResponseEntity<ChatResponse, T> response = chatClient.prompt()
                .system(s -> s.text(system).param("codeTable", ItemCode.promptTable()))
                .user(u -> u.text(userTemplate).params(params))
                .options(ChatOptions.builder().maxTokens(maxTokens))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .advisors(advisor -> advisor.param(LlmCallContext.FEATURE, feature))  // 호출 기록의 기능 이름 (ADR 0006)
                .call()
                .responseEntity(converter);
        responses.add(response.response());
        if (converter instanceof SalvagingOutputConverter<T> salvaging && salvaging.salvagedItems() >= 0) {
            String detail = "출력이 잘려 완성된 항목 " + salvaging.salvagedItems() + "개를 살림 (" + type.getSimpleName() + ")";
            log.warn(detail);
            issues.add(new Issue(IssueType.TRUNCATED_OUTPUT, null, detail));
        }
        return response.entity();
    }

    private ExtractionResult.Metrics metrics(long started, long firstPassMs, List<ChatResponse> responses,
                                             int retriedSegments, List<Issue> issues) {
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String model = null;
        Integer promptTokens = null;
        Integer completionTokens = null;
        for (ChatResponse response : responses) {
            if (response == null || response.getMetadata() == null) {
                continue;
            }
            model = response.getMetadata().getModel();
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = add(promptTokens, usage.getPromptTokens());
                completionTokens = add(completionTokens, usage.getCompletionTokens());
            }
        }
        Map<String, Integer> issueCounts = new TreeMap<>();
        issues.forEach(issue -> issueCounts.merge(issue.type().name(), 1, Integer::sum));
        Usage first = responses.isEmpty() || responses.getFirst() == null || responses.getFirst().getMetadata() == null
                ? null : responses.getFirst().getMetadata().getUsage();
        return new ExtractionResult.Metrics(model, PROMPT_VERSION, properties.pipelineLabel(), latencyMs,
                promptTokens, completionTokens, responses.size(), retriedSegments, issueCounts, firstPassMs,
                first == null ? null : first.getPromptTokens(), first == null ? null : first.getCompletionTokens());
    }

    private static List<String> concat(List<String> header, List<String> segments) {
        List<String> all = new ArrayList<>(header);
        all.addAll(segments);
        return all;
    }

    private static Integer add(Integer total, Integer value) {
        if (value == null) {
            return total;
        }
        return total == null ? value : total + value;
    }

    private static String loadPrompt(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트 파일을 읽지 못함: " + path, e);
        }
    }
}
