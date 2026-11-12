package com.checkupai.core.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 데모 하루 예산 상한 (ADR 0012 3절). 오늘 LLM 비용 합이 상한 이상이면 데모 호출을 막는다.
 * 확인과 호출 사이에 동시 요청이 들어오면 상한을 조금 넘을 수 있다.
 */
public class LlmBudget {

    /** @param dailyUsd 하루 상한 (달러). 0 이하면 끈다 */
    @ConfigurationProperties("checkupai.llm.budget")
    public record Properties(@DefaultValue("0.20") BigDecimal dailyUsd) {
    }

    public static class ExceededException extends RuntimeException {
        public ExceededException(BigDecimal spent, BigDecimal limit) {
            super("오늘 데모 예산($" + limit + ")을 다 썼습니다 (사용 $" + spent + "). 내일 다시 시도해 주세요.");
        }
    }

    private final Supplier<BigDecimal> spentToday;
    private final Properties properties;

    /** @param spentToday 오늘 쓴 LLM 비용 (운영에서는 {@link LlmUsage}의 오늘 합계) */
    public LlmBudget(Supplier<BigDecimal> spentToday, Properties properties) {
        this.spentToday = spentToday;
        this.properties = properties;
    }

    public void check() {
        BigDecimal limit = properties.dailyUsd();
        if (limit == null || limit.signum() <= 0) {
            return;
        }
        BigDecimal spent = spentToday.get();
        if (spent.compareTo(limit) >= 0) {
            throw new ExceededException(spent.setScale(4, RoundingMode.HALF_UP), limit);
        }
    }

    public BigDecimal dailyLimit() {
        return properties.dailyUsd();
    }
}
