package com.checkupai.app.ops;

import com.checkupai.core.llm.LlmBudget;
import com.checkupai.core.llm.LlmUsage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 운영 화면 API (ADR 0012 1절): LLM 호출 기록을 기능별·날짜별로 집계한다. 원문 없이 메타데이터만 나간다.
 *
 * <pre>curl 'localhost:8080/api/ops/llm-usage?days=14'</pre>
 */
@RestController
@RestControllerAdvice
public class OpsController {

    public record UsageResponse(int days, Instant since, BigDecimal todayCostUsd, BigDecimal dailyBudgetUsd,
                                List<LlmUsage.FeatureUsage> byFeature, List<LlmUsage.DailyUsage> daily) {
    }

    private final LlmUsage usage;
    private final LlmBudget budget;

    public OpsController(LlmUsage usage, LlmBudget budget) {
        this.usage = usage;
        this.budget = budget;
    }

    @GetMapping("/api/ops/llm-usage")
    public UsageResponse llmUsage(@RequestParam(defaultValue = "14") int days) {
        int span = Math.clamp(days, 1, 90);
        Instant since = usage.startOfDaysAgo(span);
        return new UsageResponse(span, since, usage.costSince(usage.startOfToday()), budget.dailyLimit(),
                usage.byFeature(since), usage.daily(since));
    }

    /** 데모 하루 예산을 다 쓰면 429로 알린다 (ADR 0012 3절) */
    @ExceptionHandler(LlmBudget.ExceededException.class)
    public ProblemDetail budgetExceeded(LlmBudget.ExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }
}
