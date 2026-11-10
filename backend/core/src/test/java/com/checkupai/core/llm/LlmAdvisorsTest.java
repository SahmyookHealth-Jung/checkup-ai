package com.checkupai.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.checkupai.core.llm.LlmPricingProperties.Price;
import com.checkupai.core.privacy.PersonalInfoRedactionAdvisor;
import com.checkupai.core.privacy.PersonalInfoRedactor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** 가짜 ChatModel로 식별정보 제거와 호출 기록 Advisor를 함께 확인한다 */
class LlmAdvisorsTest {

    private static final LlmPricingProperties PRICING = new LlmPricingProperties(
            Map.of("claude-haiku-5-5", new Price(new BigDecimal("0.10"), new BigDecimal("0.50"))));

    private final List<LlmCallRecord> records = new ArrayList<>();

    @Test
    @DisplayName("사용자 메시지의 식별정보만 지워 보내고, 기능 이름·토큰·비용·지운 개수를 기록한다 (원문은 기록하지 않는다)")
    void redactsUserMessageAndRecordsMetadata() {
        var model = new FakeChatModel("claude-haiku-5-5", 1000, 200);

        String answer = client(model).prompt()
                .system("지침: 이름, 수검번호는 옮기지 않는다")
                .user("수검번호: SYN-2026-6245  이름: 최*준 (F/42)\n공복혈당 183 mg/dL")
                .advisors(advisor -> advisor.param(LlmCallContext.FEATURE, "report.extraction.first-pass"))
                .call()
                .content();

        assertThat(answer).isEqualTo("응답");
        assertThat(model.lastPrompt.getUserMessage().getText())
                .isEqualTo("수검번호: [수검번호]  이름: [이름] (F/42)\n공복혈당 183 mg/dL");
        assertThat(model.lastPrompt.getInstructions())
                .filteredOn(message -> message.getMessageType() == MessageType.SYSTEM)
                .extracting(message -> message.getText())
                .containsExactly("지침: 이름, 수검번호는 옮기지 않는다");

        assertThat(records).hasSize(1);
        LlmCallRecord call = records.getFirst();
        assertThat(call.feature()).isEqualTo("report.extraction.first-pass");
        assertThat(call.model()).isEqualTo("claude-haiku-5-5");
        assertThat(call.inputTokens()).isEqualTo(1000);
        assertThat(call.outputTokens()).isEqualTo(200);
        assertThat(call.costUsd()).isEqualByComparingTo("0.0002");  // (1000 × 0.10 + 200 × 0.50) / 100만
        assertThat(call.status()).isEqualTo(LlmCallRecord.Status.SUCCESS);
        assertThat(call.redactions()).containsExactlyInAnyOrderEntriesOf(Map.of("NAME", 1, "RECORD_NUMBER", 1));
    }

    @Test
    @DisplayName("모델 호출이 실패해도 오류 종류와 지연을 기록하고 예외는 그대로 던진다. 기능 이름이 없으면 unknown")
    void recordsFailedCall() {
        ChatModel failing = prompt -> {
            throw new IllegalStateException("모델 오류");
        };

        assertThatThrownBy(() -> client(failing).prompt().user("공복혈당 183").call().content())
                .isInstanceOf(IllegalStateException.class);

        assertThat(records).singleElement().satisfies(call -> {
            assertThat(call.status()).isEqualTo(LlmCallRecord.Status.ERROR);
            assertThat(call.errorType()).isEqualTo("IllegalStateException");
            assertThat(call.feature()).isEqualTo("unknown");
            assertThat(call.costUsd()).isNull();
        });
    }

    @Test
    @DisplayName("응답의 모델 이름에 날짜가 붙어도 가장 긴 접두어로 가격을 찾고, 가격표에 없으면 비용은 null")
    void findsPriceByLongestPrefix() {
        assertThat(PRICING.cost("claude-haiku-5-5-20261101", 1_000_000, 0)).isEqualByComparingTo("0.10");
        assertThat(PRICING.cost("unknown-model", 100, 100)).isNull();
        assertThat(PRICING.cost("claude-haiku-5-5", null, 100)).isNull();
    }

    @Test
    @DisplayName("지운 개수는 종류 이름 순서의 JSON으로 저장한다")
    void serializesRedactionCounts() {
        assertThat(JdbcLlmCallRecorder.toJson(Map.of("RECORD_NUMBER", 1, "NAME", 2))).isEqualTo("{\"NAME\":2,\"RECORD_NUMBER\":1}");
        assertThat(JdbcLlmCallRecorder.toJson(Map.of())).isEqualTo("{}");
    }

    private ChatClient client(ChatModel model) {
        return ChatClient.builder(model)
                .defaultAdvisors(new PersonalInfoRedactionAdvisor(new PersonalInfoRedactor()),
                        new LlmCallLoggingAdvisor(records::add, PRICING))
                .build();
    }

    /** 받은 프롬프트를 기록하고, 모델 이름과 토큰 사용량이 든 응답을 돌려준다 */
    private static final class FakeChatModel implements ChatModel {
        private final String model;
        private final int inputTokens;
        private final int outputTokens;
        private Prompt lastPrompt;

        FakeChatModel(String model, int inputTokens, int outputTokens) {
            this.model = model;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            var metadata = ChatResponseMetadata.builder()
                    .model(model)
                    .usage(new DefaultUsage(inputTokens, outputTokens))
                    .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("응답"))), metadata);
        }
    }
}
