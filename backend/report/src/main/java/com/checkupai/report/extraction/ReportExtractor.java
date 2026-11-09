package com.checkupai.report.extraction;

import com.checkupai.report.extraction.ExtractionProperties.Pipeline;
import com.checkupai.report.extraction.ExtractionValidator.Issue;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 결과지 텍스트에서 검사 항목을 추출한다 (ADR 0003).
 *
 * <ul>
 *   <li>baseline: 구조화 출력(JSON 스키마 강제) 한 번. 기준선 재현용</li>
 *   <li>validated-v2: 기준선 + 원문 대조 검증 + 누락 의심 조각만 한 번 더 요청</li>
 * </ul>
 * 모델 공급자는 모른다. app 모듈 설정(spring.ai.model.chat)이 정한다.
 *
 * <p>v1 → v2 변경 (2026-11-08 평가에서 재요청이 35분 폭주한 문제 대응):
 * 재요청에는 결과지 전문 대신 표 머리 줄과 누락 조각만 보낸다. 호출마다 출력 토큰 상한을 둔다.
 * 재요청이 실패해도 1차 결과는 반환한다.
 */
@Service
public class ReportExtractor {

    private static final Logger log = LoggerFactory.getLogger(ReportExtractor.class);

    static final String PROMPT_VERSION = "extraction-v1";

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
        return new ExtractionInfo(defaultModel, PROMPT_VERSION, properties.pipeline().label());
    }

    public ExtractionResult extract(String reportText) {
        return extract(reportText, false);
    }

    public ExtractionResult extract(String reportText, boolean withDiagnostics) {
        long started = System.nanoTime();
        List<ChatResponse> responses = new ArrayList<>();

        CheckupReport firstPass = call(systemPrompt, USER_TEMPLATE, Map.of("report", reportText),
                properties.firstPassMaxTokens(), CheckupReport.class, responses);
        if (properties.pipeline() == Pipeline.BASELINE) {
            var diagnostics = withDiagnostics
                    ? new ExtractionResult.Diagnostics(firstPass.items(), List.of(), null, null, null) : null;
            return new ExtractionResult(firstPass, metrics(started, responses, 0, List.of()), diagnostics);
        }

        ValidationResult validation = validator.validate(firstPass, reportText);
        List<Issue> issues = new ArrayList<>(validation.issues());
        CheckupReport report = validation.report();
        List<String> segments = validation.uncoveredSegments();
        List<String> header = SourceText.headerLines(reportText);
        List<ReportItem> retryItems = null;
        String retryError = null;

        if (!segments.isEmpty()) {
            try {
                ItemList retried = call(retryPrompt, RETRY_USER_TEMPLATE,
                        Map.of("header", header.isEmpty() ? "(없음)" : String.join("\n", header),
                                "segments", String.join("\n", segments)),
                        properties.retryMaxTokens(), ItemList.class, responses);
                retryItems = retried == null ? List.of() : retried.items();
                ValidationResult merged = validator.validateAdditional(report, retryItems, reportText);
                issues.addAll(merged.issues());
                report = merged.report();
            } catch (RuntimeException e) {
                // 재요청 실패(출력 상한 초과로 JSON이 잘림, 시간 초과 등)는 치명적이지 않다. 1차 결과를 그대로 쓴다
                retryError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("누락 조각 재요청 실패, 1차 결과를 반환한다: {}", retryError);
            }
        }

        var diagnostics = withDiagnostics
                ? new ExtractionResult.Diagnostics(firstPass.items(), issues,
                        segments.isEmpty() ? null : concat(header, segments), retryItems, retryError)
                : null;
        return new ExtractionResult(report, metrics(started, responses, segments.size(), issues), diagnostics);
    }

    private <T> T call(String system, String userTemplate, Map<String, Object> params, int maxTokens, Class<T> type,
                       List<ChatResponse> responses) {
        ResponseEntity<ChatResponse, T> response = chatClient.prompt()
                .system(s -> s.text(system).param("codeTable", ItemCode.promptTable()))
                .user(u -> u.text(userTemplate).params(params))
                .options(ChatOptions.builder().maxTokens(maxTokens))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .call()
                .responseEntity(type);
        responses.add(response.response());
        return response.entity();
    }

    private ExtractionResult.Metrics metrics(long started, List<ChatResponse> responses, int retriedSegments,
                                             List<Issue> issues) {
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
        return new ExtractionResult.Metrics(model, PROMPT_VERSION, properties.pipeline().label(), latencyMs,
                promptTokens, completionTokens, responses.size(), retriedSegments, issueCounts);
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
