package com.checkupai.core;

import com.checkupai.core.llm.JdbcLlmCallRecorder;
import com.checkupai.core.llm.LlmCallLoggingAdvisor;
import com.checkupai.core.llm.LlmCallRecorder;
import com.checkupai.core.llm.LlmPricingProperties;
import com.checkupai.core.privacy.PersonalInfoRedactionAdvisor;
import com.checkupai.core.privacy.PersonalInfoRedactor;
import com.checkupai.core.privacy.PrivacyProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import javax.sql.DataSource;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 모든 기능의 ChatClient에 식별정보 제거와 호출 기록을 건다 (ADR 0006).
 * Spring AI가 자동 구성하는 ChatClient.Builder에 {@link ChatClientBuilderCustomizer}로 기본 Advisor를 추가하므로,
 * 기능 모듈은 주입받은 Builder로 ChatClient를 만들기만 하면 된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LlmPricingProperties.class, PrivacyProperties.class})
public class CoreConfiguration {

    @Bean
    PersonalInfoRedactor personalInfoRedactor() {
        return new PersonalInfoRedactor();
    }

    @Bean
    LlmCallRecorder llmCallRecorder(DataSource dataSource) {
        return new JdbcLlmCallRecorder(dataSource);
    }

    @Bean
    ChatClientBuilderCustomizer llmGuardrailCustomizer(PersonalInfoRedactor redactor, LlmCallRecorder recorder,
                                                       LlmPricingProperties pricing, PrivacyProperties privacy) {
        List<Advisor> advisors = new ArrayList<>();
        if (privacy.redactionEnabled()) {  // 끄는 것은 실험용뿐 (PrivacyProperties)
            advisors.add(new PersonalInfoRedactionAdvisor(redactor));
        }
        advisors.add(new LlmCallLoggingAdvisor(recorder, pricing));
        return builder -> builder.defaultAdvisors(advisors);
    }
}
