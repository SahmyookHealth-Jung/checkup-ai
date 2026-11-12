package com.checkupai.report.followup;

import com.checkupai.core.llm.LlmBudget;
import com.checkupai.report.explanation.DemoSamples;
import com.checkupai.report.extraction.ItemCode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 화면 데모의 후속 질문 (ADR 0012 2절). 합성 샘플에만 열고, 결과지는 서버가 추출한 것을 쓴다.
 *
 * <pre>
 * curl -X POST -H 'Content-Type: application/json' localhost:8080/api/demo/samples/std-001/followup \
 *   -d '{"question": "eGFR가 뭐예요?", "itemCodes": ["EGFR"]}'
 * </pre>
 */
@RestController
@RequestMapping("/api/demo/samples")
public class DemoFollowupController {

    static final int MAX_QUESTION_CHARS = 300;

    /** @param itemCodes 화면에서 고른 항목. 비우면 질문과 결과지로 정한다 */
    public record FollowupRequest(String question, List<ItemCode> itemCodes) {
    }

    private final DemoSamples samples;
    private final FollowupService service;
    private final LlmBudget budget;

    public DemoFollowupController(DemoSamples samples, FollowupService service, LlmBudget budget) {
        this.samples = samples;
        this.service = service;
        this.budget = budget;
    }

    @PostMapping("/{id}/followup")
    public FollowupService.FollowupResult followup(@PathVariable String id, @RequestBody FollowupRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문이 없습니다");
        }
        if (request.question().length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문은 " + MAX_QUESTION_CHARS + "자까지 받습니다");
        }
        budget.check();
        return service.answer(samples.extraction(id).report(), request.question().strip(), request.itemCodes());
    }
}
