package com.checkupai.core.llm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

/**
 * 모든 ChatClient 호출의 토큰·비용·지연을 기록한다 (CLAUDE.md "LLM을 호출하는 모든 코드는 토큰, 비용, 지연시간을 기록한다",
 * ADR 0006). 기능 코드는 기능 이름만 붙인다({@link LlmCallContext#FEATURE}). 실패한 호출도 오류 종류와 함께 남긴다.
 */
public class LlmCallLoggingAdvisor implements CallAdvisor {

    /** 식별정보 제거 Advisor 다음(안쪽)에 돌아, 제거 결과를 함께 남긴다 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2000;

    private final LlmCallRecorder recorder;
    private final LlmPricingProperties pricing;

    public LlmCallLoggingAdvisor(LlmCallRecorder recorder, LlmPricingProperties pricing) {
        this.recorder = recorder;
        this.pricing = pricing;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Instant calledAt = Instant.now();
        long started = System.nanoTime();
        String feature = String.valueOf(request.context().getOrDefault(LlmCallContext.FEATURE,
                LlmCallContext.UNKNOWN_FEATURE));
        Map<String, Integer> redactions = redactions(request.context().get(LlmCallContext.REDACTIONS));
        String requestedModel = request.prompt().getOptions() == null ? null : request.prompt().getOptions().getModel();
        try {
            ChatClientResponse response = chain.nextCall(request);
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            ChatResponse chat = response.chatResponse();
            String model = chat != null && chat.getMetadata() != null && chat.getMetadata().getModel() != null
                    && !chat.getMetadata().getModel().isBlank() ? chat.getMetadata().getModel() : requestedModel;
            Usage usage = chat == null || chat.getMetadata() == null ? null : chat.getMetadata().getUsage();
            Integer input = usage == null ? null : usage.getPromptTokens();
            Integer output = usage == null ? null : usage.getCompletionTokens();
            BigDecimal cost = pricing.cost(model, input, output);
            recorder.record(new LlmCallRecord(calledAt, feature, model, input, output, latencyMs, cost,
                    LlmCallRecord.Status.SUCCESS, null, redactions));
            return response;
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            recorder.record(new LlmCallRecord(calledAt, feature, requestedModel, null, null, latencyMs, null,
                    LlmCallRecord.Status.ERROR, e.getClass().getSimpleName(), redactions));
            throw e;
        }
    }

    /** 문맥에는 PersonalInfoType 키 맵이 들어 있다. core 밖 의존 없이 이름만 꺼낸다 */
    private static Map<String, Integer> redactions(Object value) {
        Map<String, Integer> result = new TreeMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((type, count) -> {
                if (count instanceof Integer number && number > 0) {
                    result.put(String.valueOf(type), number);
                }
            });
        }
        return result;
    }

    @Override
    public String getName() {
        return "LlmCallLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
