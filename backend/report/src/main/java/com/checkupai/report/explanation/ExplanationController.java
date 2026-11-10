package com.checkupai.report.explanation;

import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.extraction.ReportExtractor;
import com.checkupai.report.extraction.ReportTextReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 결과지 해설 API: 추출 → 판정 → 근거 검색 → 출처 붙은 해설 (ADR 0007).
 *
 * <pre>
 * curl localhost:8080/api/demo/samples
 * curl -X POST localhost:8080/api/demo/samples/std-001/explain
 * curl -F file=@std-001.pdf localhost:8080/api/reports/explain
 * </pre>
 * 자유 질문은 받지 않는다 (가드레일, ADR 0007 5절).
 */
@RestController
@RequestMapping("/api")
@EnableConfigurationProperties({ExplanationProperties.class, DemoProperties.class})
public class ExplanationController {

    private static final Pattern SAMPLE_ID = Pattern.compile("[a-z]+-\\d{3}");

    private final ReportTextReader textReader;
    private final ReportExtractor extractor;
    private final ExplanationService explanation;
    private final DemoProperties demo;

    public ExplanationController(ReportTextReader textReader, ReportExtractor extractor, ExplanationService explanation,
                                 DemoProperties demo) {
        this.textReader = textReader;
        this.extractor = extractor;
        this.explanation = explanation;
        this.demo = demo;
    }

    public record ExplainResponse(String sampleId, ExtractionResult extraction, ExplanationResult explanation) {
    }

    @PostMapping(path = "/reports/explain", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExplainResponse explain(@RequestPart("file") MultipartFile file) throws IOException {
        ExtractionResult extraction = extractor.extract(textReader.read(file.getBytes(), file.getOriginalFilename()));
        return new ExplainResponse(null, extraction, explanation.explain(extraction));
    }

    /** 합성 샘플 결과지 목록 (화면의 선택 상자) */
    @GetMapping("/demo/samples")
    public List<String> samples() throws IOException {
        Path dir = Path.of(demo.samplesDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".pdf"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .filter(id -> SAMPLE_ID.matcher(id).matches())
                    .sorted()
                    .toList();
        }
    }

    @PostMapping("/demo/samples/{id}/explain")
    public ExplainResponse explainSample(@PathVariable String id) throws IOException {
        if (!SAMPLE_ID.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "샘플 id 형식이 아니다: " + id);
        }
        Path pdf = Path.of(demo.samplesDir(), id + ".pdf");
        if (!Files.exists(pdf)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "샘플이 없다: " + id);
        }
        ExtractionResult extraction = extractor.extract(textReader.read(Files.readAllBytes(pdf), id + ".pdf"));
        return new ExplainResponse(id, extraction, explanation.explain(extraction));
    }
}
