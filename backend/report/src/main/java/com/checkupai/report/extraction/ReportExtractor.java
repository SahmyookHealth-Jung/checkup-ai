package com.checkupai.report.extraction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 결과지 텍스트에서 검사 항목을 추출한다.
 *
 * <p>기준선(baseline) 버전: 구조화 출력(JSON 스키마 강제)만 쓴다. 검증·재시도는 다음 단계에서 붙인다 (ADR 0003).
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

    private final ChatClient chatClient;
    private final String systemPrompt;

    public ReportExtractor(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.systemPrompt = loadPrompt("prompts/report-extraction-v1.st");
    }

    public ExtractionResult extract(String reportText) {
        long started = System.nanoTime();

        ResponseEntity<ChatResponse, CheckupReport> response = chatClient.prompt()
                .system(s -> s.text(systemPrompt).param("codeTable", ItemCode.promptTable()))
                .user(u -> u.text(USER_TEMPLATE).param("report", reportText))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .call()
                .responseEntity(CheckupReport.class);

        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        return new ExtractionResult(response.entity(), metrics(response.response(), latencyMs));
    }

    private static ExtractionResult.Metrics metrics(ChatResponse chatResponse, long latencyMs) {
        String model = null;
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (chatResponse != null && chatResponse.getMetadata() != null) {
            model = chatResponse.getMetadata().getModel();
            Usage usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens();
            }
        }
        return new ExtractionResult.Metrics(model, PROMPT_VERSION, latencyMs, promptTokens, completionTokens, 1);
    }

    private static String loadPrompt(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트 파일을 읽지 못함: " + path, e);
        }
    }
}
