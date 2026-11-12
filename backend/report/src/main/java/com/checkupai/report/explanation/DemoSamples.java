package com.checkupai.report.explanation;

import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.extraction.ReportExtractor;
import com.checkupai.report.extraction.ReportTextReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 화면 데모의 합성 샘플 결과지 (ADR 0007 7절, ADR 0012 2절).
 * 샘플마다 추출 결과를 메모리에 두고 해설과 후속 질문이 같이 쓴다. 후속 질문은 클라이언트가 보낸 결과지가 아니라
 * 이 추출 결과를 쓰므로, 검증을 거치지 않은 값이 LLM에 가지 않는다. 앱을 다시 띄우면 사라진다.
 */
@Component
public class DemoSamples {

    private static final Pattern SAMPLE_ID = Pattern.compile("[a-z]+-\\d{3}");

    private final DemoProperties demo;
    private final ReportTextReader textReader;
    private final ReportExtractor extractor;
    private final Map<String, ExtractionResult> extractions = new ConcurrentHashMap<>();

    public DemoSamples(DemoProperties demo, ReportTextReader textReader, ReportExtractor extractor) {
        this.demo = demo;
        this.textReader = textReader;
        this.extractor = extractor;
    }

    public List<String> ids() throws IOException {
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

    /** 샘플의 추출 결과. 처음이면 추출하고(LLM 호출) 기억한다 */
    public ExtractionResult extraction(String id) {
        if (!SAMPLE_ID.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "샘플 id 형식이 아니다: " + id);
        }
        Path pdf = Path.of(demo.samplesDir(), id + ".pdf");
        if (!Files.exists(pdf)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "샘플이 없다: " + id);
        }
        return extractions.computeIfAbsent(id, key -> {
            try {
                return extractor.extract(textReader.read(Files.readAllBytes(pdf), key + ".pdf"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
