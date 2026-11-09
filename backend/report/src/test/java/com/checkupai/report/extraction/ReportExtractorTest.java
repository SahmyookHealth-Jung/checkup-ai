package com.checkupai.report.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkupai.report.extraction.ExtractionProperties.Pipeline;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** 모델 대신 정해 둔 응답을 돌려주는 가짜 ChatModel로 추출 흐름을 확인한다. */
class ReportExtractorTest {

    private static final String SOURCE = """
            검사명 금회 단위
            요산 5.4 mg/dL
            알부민 4.9 g/dL
            TSH 4.42 μIU/mL
            총빌리루빈 0.9 mg/dL
            CEA 3.3 ng/mL
            hs-CRP 1.6 mg/L
            """;

    @Test
    @DisplayName("누락 조각이 6개면 4개, 2개로 나눠 재요청하고 결과를 합친다 (v2.3)")
    void retriesMissingSegmentsInBatches() {
        var model = new ScriptedChatModel(
                "{\"examType\":\"COMPREHENSIVE\",\"sex\":\"MALE\",\"age\":50,\"items\":[]}",
                "{\"items\":[{\"code\":\"OTHER\",\"rawName\":\"요산\",\"value\":\"5.4\"}]}",
                "{\"items\":[{\"code\":\"OTHER\",\"rawName\":\"hs-CRP\",\"value\":\"1.6\"}]}");

        ExtractionResult result = extractor(model).extract(SOURCE, true);

        assertThat(model.userMessages).hasSize(3);
        assertThat(model.userMessages.get(1)).contains("요산 5.4", "총빌리루빈 0.9").doesNotContain("CEA 3.3");
        assertThat(model.userMessages.get(2)).contains("CEA 3.3", "hs-CRP 1.6").doesNotContain("요산 5.4");
        assertThat(result.report().items()).extracting(ReportItem::value).containsExactly("5.4", "1.6");
        assertThat(result.metrics().attempts()).isEqualTo(3);
        assertThat(result.metrics().retriedSegments()).isEqualTo(6);
    }

    @Test
    @DisplayName("재요청 한 묶음이 실패해도 다른 묶음의 결과는 남긴다")
    void keepsOtherBatchesWhenOneFails() {
        var model = new ScriptedChatModel(
                "{\"examType\":\"COMPREHENSIVE\",\"sex\":\"MALE\",\"age\":50,\"items\":[]}",
                "깨진 응답",
                "{\"items\":[{\"code\":\"OTHER\",\"rawName\":\"hs-CRP\",\"value\":\"1.6\"}]}");

        ExtractionResult result = extractor(model).extract(SOURCE, true);

        assertThat(result.report().items()).extracting(ReportItem::value).containsExactly("1.6");
        assertThat(result.diagnostics().retryError()).isNotNull();
    }

    private static ReportExtractor extractor(ChatModel model) {
        var properties = new ExtractionProperties(Pipeline.VALIDATED, 2500, 600, 4);
        return new ReportExtractor(ChatClient.builder(model), model, new ExtractionValidator(), properties);
    }

    /** 호출 순서대로 정해 둔 응답을 돌려주고, 받은 사용자 메시지를 기록한다 */
    private static final class ScriptedChatModel implements ChatModel {
        private final List<String> responses;
        private final List<String> userMessages = new ArrayList<>();

        ScriptedChatModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            userMessages.add(prompt.getUserMessage().getText());
            String text = responses.get(Math.min(userMessages.size() - 1, responses.size() - 1));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }
}
