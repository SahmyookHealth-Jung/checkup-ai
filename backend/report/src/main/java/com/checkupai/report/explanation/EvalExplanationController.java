package com.checkupai.report.explanation;

import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.retrieval.GuidelineProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 저장한 추출 결과로 해설만 돌리는 평가 API (ADR 0008 4절 2번).
 *
 * <p>추출(Haiku)은 실행마다 흔들린다. 추출부터 다시 하면 해설 개선의 효과와 추출의 흔들림이 섞인다.
 * 그래서 추출 결과를 한 번 저장해 고정하고, 해설 조건만 바꿔 짝비교한다 (eval/rag/freeze_extractions.py).
 *
 * <pre>
 * curl -X POST -H 'Content-Type: application/json' -d @lab-001.json localhost:8080/api/eval/explain
 * curl -X POST -H 'Content-Type: application/json' -d @lab-001.json 'localhost:8080/api/eval/explain?itemFilter=true'
 * </pre>
 * 조건(itemFilter, codeTemplates)을 주지 않으면 설정 기본값을 쓴다. 앱을 다시 띄우지 않고 조건을 바꿔 짝비교한다.
 */
@RestController
@RequestMapping("/api/eval")
@EnableConfigurationProperties(EvalProperties.class)
public class EvalExplanationController {

    private final ExplanationService explanation;
    private final EvalProperties properties;
    private final ExplanationProperties explanationProperties;
    private final GuidelineProperties guidelineProperties;

    public EvalExplanationController(ExplanationService explanation, EvalProperties properties,
                                     ExplanationProperties explanationProperties,
                                     GuidelineProperties guidelineProperties) {
        this.explanation = explanation;
        this.properties = properties;
        this.explanationProperties = explanationProperties;
        this.guidelineProperties = guidelineProperties;
    }

    /**
     * 해설 결과를 바꾸는 설정. 평가 기록에 함께 남긴다 (조건 비교의 근거).
     *
     * @param itemFilter 조건을 주지 않았을 때의 기본값
     * @param codeTemplates 조건을 주지 않았을 때의 기본값
     * @param chunksSha256 근거 청크 파일의 해시 앞 16자. 적재한 청크와 같은지는 적재(reload) 뒤 이 파일을 바꾸지 않았다는 전제다
     */
    public record ExplanationInfo(String promptVersion, int maxItems, int maxTokens, int topK, double minSimilarity,
                                  boolean itemFilter, boolean codeTemplates, String chunksSha256) {
    }

    @GetMapping("/explain/info")
    public ExplanationInfo info() {
        requireEnabled();
        return new ExplanationInfo(ExplanationService.PROMPT_VERSION, explanationProperties.maxItems(),
                explanationProperties.maxTokens(), guidelineProperties.topK(), guidelineProperties.minSimilarity(),
                explanationProperties.itemFilter(), explanationProperties.codeTemplates(),
                sha256(Path.of(guidelineProperties.chunksFile())));
    }

    /** 요청 본문은 추출 API 응답과 같은 모양이다. report와 needsReview만 쓴다 */
    @PostMapping("/explain")
    public ExplanationResult explain(@RequestBody ExtractionResult extraction,
                                     @RequestParam(required = false) Boolean itemFilter,
                                     @RequestParam(required = false) Boolean codeTemplates) {
        requireEnabled();
        if (extraction == null || extraction.report() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "report가 없다");
        }
        ExplanationService.Options defaults = explanation.defaultOptions();
        var options = new ExplanationService.Options(
                itemFilter == null ? defaults.itemFilter() : itemFilter,
                codeTemplates == null ? defaults.codeTemplates() : codeTemplates);
        return explanation.explain(new ExtractionResult(extraction.report(), null, null, extraction.needsReview()),
                options);
    }

    static String sha256(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private void requireEnabled() {
        if (!properties.endpointsEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
