package com.checkupai.stats.query;

import com.checkupai.core.llm.LlmBudget;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관리자 통계 어시스턴트 API (ADR 0013).
 *
 * <pre>
 * curl -X POST -H 'Content-Type: application/json' localhost:8080/api/stats/ask -d '{"question": "시도별 수검자 수"}'
 * </pre>
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    static final int MAX_QUESTION_CHARS = 300;

    public record AskRequest(String question) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StatsProperties.class)
    static class StatsConfiguration {
        @Bean
        StatsDatabase statsDatabase(StatsProperties properties) {
            return new StatsDatabase(properties);
        }
    }

    private final StatsService service;
    private final LlmBudget budget;

    public StatsController(StatsService service, LlmBudget budget) {
        this.service = service;
        this.budget = budget;
    }

    @PostMapping("/ask")
    public StatsService.StatsResult ask(@RequestBody AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문이 없습니다");
        }
        if (request.question().length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문은 " + MAX_QUESTION_CHARS + "자까지 받습니다");
        }
        budget.check();
        return service.ask(request.question().strip());
    }
}
