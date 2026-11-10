package com.checkupai.app;

import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ThinkingConfigParam;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/**
 * {@code spring.ai.anthropic.chat.options.thinking}을 문자열({@code disabled} | {@code adaptive})로 설정할 수 있게 한다.
 *
 * <p>Spring AI 2.0.1은 이 속성을 Anthropic SDK의 {@link ThinkingConfigParam}(공용체 타입)으로 받지만,
 * 문자열에서 바꾸는 변환기가 없어 application.yml에 쓸 수 없다. 공급자 설정은 app 모듈의 몫이므로 여기에 둔다.
 *
 * <p>2단계 모델 비교(ADR 0005): qwen3:4b(think: false)와 같은 조건으로 Haiku 5.5의 thinking을 끈다.
 * Haiku 5.5는 thinking을 생략하면 adaptive로 돈다(사고 토큰이 출력 상한과 비용을 함께 쓴다).
 */
@Configuration(proxyBeanMethods = false)
class AnthropicThinkingConfig {

    @Bean
    @ConfigurationPropertiesBinding
    static ThinkingConverter anthropicThinkingConverter() {
        return new ThinkingConverter();
    }

    /** 람다로 만들면 제네릭 타입 정보가 사라져 바인딩에 등록되지 않으므로 클래스로 둔다 */
    static final class ThinkingConverter implements Converter<String, ThinkingConfigParam> {

        @Override
        public ThinkingConfigParam convert(String value) {
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "disabled" -> ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder().build());
                case "adaptive" -> ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build());
                default -> throw new IllegalArgumentException("thinking은 disabled 또는 adaptive만 쓸 수 있다: " + value);
            };
        }
    }
}
