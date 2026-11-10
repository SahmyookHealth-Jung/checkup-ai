package com.checkupai.report.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.checkupai.report.extraction.ExtractionProperties.Pipeline;
import com.checkupai.report.extraction.ExtractionValidator.Issue;
import com.checkupai.report.extraction.ExtractionValidator.IssueType;
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

    @Test
    @DisplayName("재요청을 끄면 1차 호출만 하고, 누락 조각은 진단 기록에만 남긴다 (ADR 0005)")
    void recordsMissingSegmentsWithoutRetryWhenDisabled() {
        var model = new ScriptedChatModel(
                "{\"examType\":\"COMPREHENSIVE\",\"sex\":\"MALE\",\"age\":50,\"items\":[]}");

        ExtractionResult result = extractor(model, false).extract(SOURCE, true);

        assertThat(model.userMessages).hasSize(1);
        assertThat(result.metrics().attempts()).isEqualTo(1);
        assertThat(result.metrics().retriedSegments()).isZero();
        assertThat(result.metrics().pipeline()).isEqualTo("validated-v2.4-noretry");
        assertThat(result.diagnostics().retryInput()).contains("요산 5.4 mg/dL", "hs-CRP 1.6 mg/L");
        assertThat(result.diagnostics().retryItems()).isNull();
    }

    @Test
    @DisplayName("재요청을 꺼도 1차 출력이 상한에서 잘렸으면 잘린 뒷부분을 재요청으로 채운다 (ADR 0004 4절)")
    void retriesTruncatedFirstPassEvenWhenRetryDisabled() {
        var model = new ScriptedChatModel(
                "{\"examType\":\"COMPREHENSIVE\",\"sex\":\"MALE\",\"age\":50,\"items\":["
                        + "{\"code\":\"OTHER\",\"rawName\":\"요산\",\"value\":\"5.4\"},{\"code\":\"OTHER\",\"rawN",
                "{\"items\":[{\"code\":\"OTHER\",\"rawName\":\"알부민\",\"value\":\"4.9\"}]}",
                "{\"items\":[]}");

        ExtractionResult result = extractor(model, false).extract(SOURCE, true);

        assertThat(model.userMessages).hasSize(3);  // 1차 + 잘린 뒷부분 5조각을 4개·1개로 나눠 재요청
        assertThat(result.report().items()).extracting(ReportItem::value).containsExactly("5.4", "4.9");
        assertThat(result.diagnostics().issues()).extracting(Issue::type).contains(IssueType.TRUNCATED_OUTPUT);
    }

    @Test
    @DisplayName("1차 응답을 결과지 형식으로 읽지 못하면 원인을 밝혀 실패한다 (NPE가 아니라)")
    void failsClearlyWhenFirstPassIsEmpty() {
        var model = new ScriptedChatModel("null");

        assertThatThrownBy(() -> extractor(model).extract(SOURCE, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1차 추출 응답");
    }

    @Test
    @DisplayName("설정 요약에 출력 상한·묶음 크기·모델 공급자 설정이 들어가 평가 캐시가 설정 변경을 구분한다")
    void settingsLabelDistinguishesResultChangingSettings() {
        var haiku = new ExtractionProperties(Pipeline.VALIDATED, 2500, 600, 4, false, "thinking=disabled");
        var adaptive = new ExtractionProperties(Pipeline.VALIDATED, 2500, 600, 4, false, "thinking=adaptive");

        assertThat(haiku.settingsLabel())
                .isEqualTo("firstPassMaxTokens=2500,retryMaxTokens=600,retryBatchSize=4,thinking=disabled")
                .isNotEqualTo(adaptive.settingsLabel());
    }

    private static ReportExtractor extractor(ChatModel model) {
        return extractor(model, true);
    }

    private static ReportExtractor extractor(ChatModel model, boolean retryEnabled) {
        var properties = new ExtractionProperties(Pipeline.VALIDATED, 2500, 600, 4, retryEnabled, "");
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
