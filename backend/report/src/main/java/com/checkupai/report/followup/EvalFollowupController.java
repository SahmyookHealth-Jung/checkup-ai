package com.checkupai.report.followup;

import com.checkupai.report.explanation.EvalProperties;
import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.followup.FollowupContextBuilder.FollowupContext;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 후속 질문 평가 API (ADR 0011 5절). 고정한 추출 결과와 질문으로 문맥만 만든다. LLM을 부르지 않는다.
 *
 * <pre>
 * curl -X POST -H 'Content-Type: application/json' localhost:8080/api/eval/followup/context \
 *   -d '{"report": {...}, "question": "eGFR 44면 무슨 뜻이에요?", "itemCodes": ["EGFR"], "budgetChars": 8000}'
 * </pre>
 */
@RestController
@RequestMapping("/api/eval/followup")
@EnableConfigurationProperties(FollowupProperties.class)
public class EvalFollowupController {

    /**
     * @param report 고정한 추출 결과의 report (평가 API와 같은 모양)
     * @param itemCodes 화면에서 고른 항목. 비우면 질문과 결과지로 정한다
     * @param budgetChars 비우면 설정값
     * @param ranking 범위 안 순위 방식. 비우면 서비스와 같은 BM25 (벡터는 비교 평가용)
     */
    public record ContextRequest(CheckupReport report, String question, List<ItemCode> itemCodes, Integer budgetChars,
                                 FollowupContextBuilder.Ranking ranking) {
    }

    private final FollowupContextBuilder builder;
    private final EvalProperties evalProperties;
    private final FollowupProperties properties;

    public EvalFollowupController(FollowupContextBuilder builder, EvalProperties evalProperties,
                                  FollowupProperties properties) {
        this.builder = builder;
        this.evalProperties = evalProperties;
        this.properties = properties;
    }

    @PostMapping("/context")
    public FollowupContext context(@RequestBody ContextRequest request) {
        if (!evalProperties.endpointsEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question이 없다");
        }
        int budget = request.budgetChars() == null ? properties.budgetChars() : request.budgetChars();
        return builder.build(request.report(), request.question(), request.itemCodes(), budget,
                request.ranking() == null ? FollowupContextBuilder.Ranking.BM25 : request.ranking());
    }
}
