package com.checkupai.report.extraction;

import com.checkupai.report.extraction.ExtractionValidator.Issue;
import com.checkupai.report.extraction.ExtractionValidator.ValidationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재생 비교: 평가에서 저장한 LLM 출력(1차·재요청)에 현재 검증기를 다시 적용한다 (ADR 0004 5절).
 * 모델을 부르지 않으므로 몇 초면 끝나고, 출력 흔들림 없이 검증 규칙의 효과만 잰다.
 *
 * <p>입력은 {@code eval/extraction/replay.py inputs}가 만든다. 보고서마다 JSON 하나:
 * {@code {"id", "pdf", "header": {examDate, examType, sex, age}, "firstPassItems", "retryItems", "retryInput"}}
 *
 * <p>한계: 저장된 재요청 출력은 그때 찾은 누락 조각에 대한 답이다. 현재 검증기가 찾은 누락 조각이 그때와 다르면
 * ({@code retryInputChanged}) 그 보고서의 재생 결과는 실제 실행과 다를 수 있다. 비교 스크립트가 따로 보여 준다.
 *
 * <p>실행 (backend/에서): {@code ./gradlew :report:replay --args="<입력 폴더> <출력 폴더>"}
 */
public final class ExtractionReplay {

    private ExtractionReplay() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("사용법: ExtractionReplay <입력 폴더> <출력 폴더>");
            System.exit(2);
        }
        Path inDir = Path.of(args[0]).toAbsolutePath();
        Path outDir = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(outDir);

        JsonMapper mapper = JsonMapper.builder().build();
        ReportTextReader reader = new ReportTextReader();
        ExtractionValidator validator = new ExtractionValidator();

        List<Path> inputs;
        try (Stream<Path> files = Files.list(inDir)) {
            inputs = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        for (Path in : inputs) {
            Input doc = mapper.readValue(in.toFile(), Input.class);
            String text = reader.read(Files.readAllBytes(Path.of(doc.pdf())), "report.pdf");
            Header header = doc.header() == null ? new Header(null, null, null, null) : doc.header();
            CheckupReport first = new CheckupReport(header.examDate(), header.examType(), header.sex(), header.age(),
                    doc.firstPassItems() == null ? List.of() : doc.firstPassItems());

            // ReportExtractor와 같은 순서: 1차 검증 → 누락 조각이 있으면 재요청 출력을 같은 검증으로 합친다.
            // 재요청을 묶음으로 나눠 보낸 경우에도 묶음 출력을 차례로 검증하는 것과 한 번에 검증하는 것은 같다.
            ValidationResult checked = validator.validate(first, text);
            List<Issue> issues = new ArrayList<>(checked.issues());
            CheckupReport result = checked.report();
            List<ReportItem> validatedItems = result.items();  // "검증만, 재요청 없음" 조건 (모델 비교용)
            if (!checked.uncoveredSegments().isEmpty() && doc.retryItems() != null) {
                ValidationResult merged = validator.validateAdditional(result, doc.retryItems(), text);
                issues.addAll(merged.issues());
                result = merged.report();
            }
            // 저장된 재요청 입력은 ReportExtractor가 기록한 형태(표 머리 줄 + 누락 조각)다. 같은 형태로 만들어 비교한다
            List<String> retryInput = new ArrayList<>();
            if (!checked.uncoveredSegments().isEmpty()) {
                retryInput.addAll(SourceText.headerLines(text));
                retryInput.addAll(checked.uncoveredSegments());
            }
            List<String> savedRetryInput = doc.retryInput() == null ? List.of() : doc.retryInput();
            mapper.writeValue(outDir.resolve(in.getFileName()).toFile(), new Output(doc.id(), result.items(),
                    validatedItems, issues, retryInput, savedRetryInput, !retryInput.equals(savedRetryInput)));
        }
        System.out.println("재생 " + inputs.size() + "개 → " + outDir);
    }

    record Header(LocalDate examDate, CheckupReport.ExamType examType, CheckupReport.Sex sex, Integer age) {
    }

    record Input(String id, String pdf, Header header, List<ReportItem> firstPassItems, List<ReportItem> retryItems,
                 List<String> retryInput) {
    }

    /**
     * @param items 최종 항목 (검증 + 재요청)
     * @param validatedItems 1차 출력을 검증만 한 항목 (재요청 전)
     * @param retryInput 현재 검증기로 만든 재요청 입력 (표 머리 줄 + 누락 조각, 누락이 없으면 빈 목록)
     * @param savedRetryInput 실제 실행에서 재요청한 조각
     * @param retryInputChanged 둘이 다르다: 이 보고서의 재생 결과는 근사치다
     */
    record Output(String id, List<ReportItem> items, List<ReportItem> validatedItems, List<Issue> issues,
                  List<String> retryInput, List<String> savedRetryInput, boolean retryInputChanged) {
    }
}
