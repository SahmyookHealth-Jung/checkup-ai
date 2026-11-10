package com.checkupai.core.privacy;

import com.checkupai.core.llm.LlmCallContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/**
 * 모델로 보내는 사용자 메시지에서 개인 식별정보를 지운다 (ADR 0006). 시스템 메시지(프롬프트 지침)는 그대로 둔다.
 *
 * <p>모든 ChatClient 호출에 걸리므로 기능 코드가 따로 신경 쓰지 않아도 된다. 공급자와 무관하게 항상 적용한다
 * (오프라인 모드도 같은 입력을 받아 결과를 비교할 수 있고, 실수로 외부 모드가 돼도 안전하다).
 * 지운 종류별 개수를 요청 문맥에 남겨 호출 기록이 함께 저장한다.
 */
public class PersonalInfoRedactionAdvisor implements CallAdvisor {

    /** 호출 기록 Advisor보다 먼저(바깥에서) 돈다 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

    private final PersonalInfoRedactor redactor;

    public PersonalInfoRedactionAdvisor(PersonalInfoRedactor redactor) {
        this.redactor = redactor;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Prompt prompt = request.prompt();
        Map<PersonalInfoType, Integer> counts = new EnumMap<>(PersonalInfoType.class);
        List<Message> messages = prompt.getInstructions().stream()
                .map(message -> message instanceof UserMessage user ? redact(user, counts) : message)
                .toList();
        ChatClientRequest redacted = request.mutate()
                .prompt(new Prompt(messages, prompt.getOptions()))
                .context(LlmCallContext.REDACTIONS, Map.copyOf(counts))
                .build();
        return chain.nextCall(redacted);
    }

    private Message redact(UserMessage user, Map<PersonalInfoType, Integer> counts) {
        PersonalInfoRedactor.Redaction redaction = redactor.redact(user.getText());
        redaction.counts().forEach((type, count) -> counts.merge(type, count, Integer::sum));
        return redaction.total() == 0 ? user : user.mutate().text(redaction.text()).build();
    }

    @Override
    public String getName() {
        return "PersonalInfoRedactionAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
