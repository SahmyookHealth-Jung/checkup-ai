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
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 결과지 텍스트에서 검사 항목을 추출한다 (ADR 0003).
 *
 * <ul>
 *   <li>baseline: 구조화 출력(JSON 스키마 강제) 한 번. 기준선 재현용</li>
 *   <li>validated-v1: 기준선 + 원문 대조 검증 + 누락 의심 줄만 한 번 더 요청</li>
 * </ul>
 * 모델 공급자는 모른다. app 모듈 설정(spring.ai.model.chat)이 정한다.
 */
@Service
public class ReportExtractor {

    static final String PROMPT_VERSION = "extraction-v1";

    private static final String USER_TEMPLATE = """
            다음 검진 결과지에서 검사 결과를 추출하라.

            <결과지>
            {report}
            </결과지>
            """;

    private static final String RETRY_USER_TEMPLATE = """
            <결과지>
            {report}
            </결과지>

            <추출할 줄>
            {lines}
            </추출할 줄>
            """;

    private final ChatClient chatClient;
    private final ExtractionValidator validator;
    private final Pipeline pipeline;
    private final String defaultModel;
    private final String systemPrompt;
    private final String retryPrompt;

    public ReportExtractor(ChatClient.Builder chatClientBuilder, ChatModel chatModel,
                           ExtractionValidator validator, ExtractionProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.validator = validator;
        this.pipeline = properties.pipeline();
        this.defaultModel = chatModel.getOptions().getModel();
        this.systemPrompt = loadPrompt("prompts/report-extraction-v1.st");
        this.retryPrompt = loadPrompt("prompts/report-extraction-retry-v1.st");
    }

    public ExtractionInfo info() {
        return new ExtractionInfo(defaultModel, PROMPT_VERSION, pipeline.label());
    }

    public ExtractionResult extract(String reportText) {
        long started = System.nanoTime();
        List<ChatResponse> responses = new ArrayList<>();

        CheckupReport extracted = call(systemPrompt, USER_TEMPLATE, Map.of("report", reportText),
                CheckupReport.class, responses);
        if (pipeline == Pipeline.BASELINE) {
            return new ExtractionResult(extracted, metrics(started, responses, 0, List.of()));
        }

        ValidationResult validation = validator.validate(extracted, reportText);
        List<Issue> issues = new ArrayList<>(validation.issues());
        CheckupReport report = validation.report();
        List<String> uncovered = validation.uncoveredLines();

        if (!uncovered.isEmpty()) {
            ItemList retried = call(retryPrompt, RETRY_USER_TEMPLATE,
                    Map.of("report", reportText, "lines", String.join("\n", uncovered)), ItemList.class, responses);
            ValidationResult merged = validator.validateAdditional(report, retried == null ? null : retried.items(),
                    reportText);
            issues.addAll(merged.issues());
            report = merged.report();
        }
        return new ExtractionResult(report, metrics(started, responses, uncovered.size(), issues));
    }

    private <T> T call(String system, String userTemplate, Map<String, Object> params, Class<T> type,
                       List<ChatResponse> responses) {
        ResponseEntity<ChatResponse, T> response = chatClient.prompt()
                .system(s -> s.text(system).param("codeTable", ItemCode.promptTable()))
                .user(u -> u.text(userTemplate).params(params))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .call()
                .responseEntity(type);
        responses.add(response.response());
        return response.entity();
    }

    private ExtractionResult.Metrics metrics(long started, List<ChatResponse> responses, int retriedLines,
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
        return new ExtractionResult.Metrics(model, PROMPT_VERSION, pipeline.label(), latencyMs, promptTokens,
                completionTokens, responses.size(), retriedLines, issueCounts);
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
