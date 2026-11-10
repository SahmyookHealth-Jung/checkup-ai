package com.checkupai.core.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 모델별 가격표 (100만 토큰당 달러). 값과 출처는 application.yml에 둔다.
 *
 * @param pricing 모델 이름 → 가격. 응답의 모델 이름이 날짜 등으로 더 길면 가장 긴 접두어로 찾는다
 */
@ConfigurationProperties("checkupai.llm")
public record LlmPricingProperties(@DefaultValue Map<String, Price> pricing) {

    /**
     * @param input 입력 100만 토큰당 달러
     * @param output 출력 100만 토큰당 달러
     */
    public record Price(BigDecimal input, BigDecimal output) {
    }

    /** 가격표에 없는 모델이거나 토큰 수를 모르면 null */
    public BigDecimal cost(String model, Integer inputTokens, Integer outputTokens) {
        if (model == null || inputTokens == null || outputTokens == null) {
            return null;
        }
        Price price = pricing.get(model);
        if (price == null) {
            price = pricing.entrySet().stream()
                    .filter(entry -> model.startsWith(entry.getKey()))
                    .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                    .map(Map.Entry::getValue)
                    .orElse(null);
        }
        if (price == null) {
            return null;
        }
        BigDecimal tokens = price.input().multiply(BigDecimal.valueOf(inputTokens))
                .add(price.output().multiply(BigDecimal.valueOf(outputTokens)));
        return tokens.divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }
}
