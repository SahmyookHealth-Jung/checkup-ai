package com.checkupai.report.explanation;

import com.checkupai.core.llm.LlmBudget;
import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.extraction.ReportExtractor;
import com.checkupai.report.extraction.ReportTextReader;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 결과지 해설 API: 추출 → 판정 → 근거 검색 → 출처 붙은 해설 (ADR 0007).
 *
 * <pre>
 * curl localhost:8080/api/demo/samples
 * curl -X POST localhost:8080/api/demo/samples/std-001/explain
 * curl -F file=@std-001.pdf localhost:8080/api/reports/explain
 * curl -X POST -H 'Content-Type: application/json' localhost:8080/api/demo/samples/std-001/followup -d '{"question":"..."}'
 * </pre>
 * 자유 질문은 합성 샘플에 대한 후속 질문으로만 받는다 ({@code DemoFollowupController}, ADR 0012 2절). 데모 호출은 하루 예산 상한을 지킨다.
 */
@RestController
@RequestMapping("/api")
@EnableConfigurationProperties({ExplanationProperties.class, DemoProperties.class})
public class ExplanationController {

    private final ReportTextReader textReader;
    private final ReportExtractor extractor;
    private final ExplanationService explanation;
    private final DemoSamples samples;
    private final LlmBudget budget;

    public ExplanationController(ReportTextReader textReader, ReportExtractor extractor, ExplanationService explanation,
                                 DemoSamples samples, LlmBudget budget) {
        this.textReader = textReader;
        this.extractor = extractor;
        this.explanation = explanation;
        this.samples = samples;
        this.budget = budget;
    }

    public record ExplainResponse(String sampleId, ExtractionResult extraction, ExplanationResult explanation) {
    }

    @PostMapping(path = "/reports/explain", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExplainResponse explain(@RequestPart("file") MultipartFile file) throws IOException {
        budget.check();
        ExtractionResult extraction = extractor.extract(textReader.read(file.getBytes(), file.getOriginalFilename()));
        // 후속 질문을 받으려고 추출 결과를 잠시 기억한다 (sampleId로 /demo/samples/{id}/followup)
        return new ExplainResponse(samples.remember(extraction), extraction, explanation.explain(extraction));
    }

    /** 합성 샘플 결과지 목록 (화면의 선택 상자) */
    @GetMapping("/demo/samples")
    public List<String> samples() throws IOException {
        return samples.ids();
    }

    @PostMapping("/demo/samples/{id}/explain")
    public ExplainResponse explainSample(@PathVariable String id) {
        budget.check();
        ExtractionResult extraction = samples.extraction(id);
        return new ExplainResponse(id, extraction, explanation.explain(extraction));
    }
}
