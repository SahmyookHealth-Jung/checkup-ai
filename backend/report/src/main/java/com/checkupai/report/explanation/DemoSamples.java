package com.checkupai.report.explanation;

import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.extraction.ReportExtractor;
import com.checkupai.report.extraction.ReportItem;
import com.checkupai.report.extraction.ReportTextReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 화면 데모의 합성 샘플 결과지 (ADR 0007 7절, ADR 0012 2절).
 * 샘플마다 추출 결과를 메모리에 두고 해설과 후속 질문이 같이 쓴다. 후속 질문은 클라이언트가 보낸 결과지가 아니라
 * 이 추출 결과를 쓰므로, 검증을 거치지 않은 값이 LLM에 가지 않는다. 앱을 다시 띄우면 사라진다.
 *
 * <p>화면에서 올린 결과지도 같은 방식으로 후속 질문을 받는다(ADR 0012 보완). 추출 결과만 메모리에 최근
 * {@value #MAX_UPLOADS}개까지 두고, 추측할 수 없는 id를 준다. 디스크·DB에는 남기지 않는다.
 */
@Component
public class DemoSamples {

    private static final Pattern SAMPLE_ID = Pattern.compile("[a-z]+-\\d{3}");
    private static final Pattern UPLOAD_ID = Pattern.compile("up-[0-9a-f]{24}");
    static final int MAX_UPLOADS = 50;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 합성기 템플릿 이름 (eval/synth/templates.py의 TEMPLATES) */
    private static final Map<String, String> TEMPLATE_NAMES = Map.of(
            "std", "공단 표준 서식", "sum", "기관 요약표", "nar", "목록 + 의사 소견");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DemoProperties demo;
    private final ReportTextReader textReader;
    private final ReportExtractor extractor;
    private final ScreeningCriteria criteria;
    private final Map<String, ExtractionResult> extractions = new ConcurrentHashMap<>();
    private final Map<String, ExtractionResult> uploads = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ExtractionResult> eldest) {
            return size() > MAX_UPLOADS;
        }
    };

    public DemoSamples(DemoProperties demo, ReportTextReader textReader, ReportExtractor extractor,
                       ScreeningCriteria criteria) {
        this.demo = demo;
        this.textReader = textReader;
        this.extractor = extractor;
        this.criteria = criteria;
    }

    /**
     * 선택 상자 한 줄. label은 합성기가 결과지에 찍은 값(정답 파일)을 앱의 판정기준표로 판정해 만든다.
     * LLM 추출 결과가 아니므로, 고른 뒤 화면의 판정(추출 실패·"확인 필요")과 다를 수 있다.
     */
    public record Sample(String id, String group, String label) {
    }

    /** 화면 선택 상자용: 양식별로 묶고, 질환의심·경계 항목 이름을 보여 준다 */
    public List<Sample> samples() throws IOException {
        List<Sample> out = new ArrayList<>();
        for (String id : ids()) {
            out.add(sample(id));
        }
        return out;
    }

    Sample sample(String id) throws IOException {
        int dash = id.indexOf('-');
        String group = TEMPLATE_NAMES.getOrDefault(id.substring(0, dash), id.substring(0, dash));
        String number = Integer.parseInt(id.substring(dash + 1)) + "번";
        Path expected = Path.of(demo.samplesDir(), id + ".expected.json");
        if (!Files.exists(expected)) {
            return new Sample(id, group, number);
        }
        CheckupReport report = JSON.readValue(Files.readString(expected), CheckupReport.class);
        List<String> suspect = new ArrayList<>();
        List<String> borderline = new ArrayList<>();
        for (ReportItem item : report.items()) {
            Judgment judgment = criteria.judge(item.code(), item.value(), item.unit(), report.sex()).judgment();
            String name = item.code().koreanName().replaceAll("\\(.*\\)", "").strip();
            if (judgment == Judgment.SUSPECT) {
                suspect.add(name);
            } else if (judgment == Judgment.NORMAL_B) {
                borderline.add(name);
            }
        }
        String sex = switch (report.sex()) {
            case MALE -> "남";
            case FEMALE -> "여";
            case UNKNOWN -> "성별 미기재";
        };
        String findings = suspect.isEmpty() && borderline.isEmpty()
                ? "질환의심·경계 없음"
                : String.join(" · ", Stream.of(names("질환의심", suspect), names("경계", borderline))
                        .filter(s -> !s.isEmpty()).toList());
        return new Sample(id, group, number + " · " + sex + " · " + findings);
    }

    /** "경계 요단백, AST, ALT 외 2". 선택 상자 한 줄에 들어가게 셋까지만 이름을 쓴다 */
    private static String names(String judgment, List<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        String shown = String.join(", ", names.subList(0, Math.min(3, names.size())));
        return judgment + " " + shown + (names.size() > 3 ? " 외 " + (names.size() - 3) : "");
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

    /** 올린 결과지의 추출 결과를 기억하고 id를 준다. 오래된 것부터 지운다 */
    public String remember(ExtractionResult extraction) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        String id = "up-" + HexFormat.of().formatHex(bytes);
        synchronized (uploads) {
            uploads.put(id, extraction);
        }
        return id;
    }

    /** 샘플의 추출 결과. 처음이면 추출하고(LLM 호출) 기억한다. 올린 결과지는 기억해 둔 것만 준다 */
    public ExtractionResult extraction(String id) {
        if (UPLOAD_ID.matcher(id).matches()) {
            synchronized (uploads) {
                ExtractionResult uploaded = uploads.get(id);
                if (uploaded == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "올린 결과지가 서버에 없습니다(앱을 다시 띄웠거나 오래됨). 다시 올려 주세요");
                }
                return uploaded;
            }
        }
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
