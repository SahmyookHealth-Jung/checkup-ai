package com.checkupai.report.extraction;

import com.checkupai.core.privacy.PersonalInfoRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 식별정보 잔존율 측정 (ADR 0006). 합성 결과지 PDF에서 뽑은 글을 외부 전송용으로 지운 뒤, 생성기가 찍어 넣은
 * 개인 식별정보(manifest.jsonl의 pii: 이름, 생년월일, 수검번호류)가 하나라도 남는지 센다. 모델을 부르지 않는다.
 * 지운 종류별 개수도 함께 보여 준다(검진기관 전화번호·주소처럼 개인 정보가 아닌 것도 지우므로 많을 수 있다).
 *
 * <p>실행 (backend/에서): {@code ./gradlew :report:piiLeakCheck --args="<데이터 폴더> ..."}
 */
public final class PersonalInfoLeakCheck {

    private PersonalInfoLeakCheck() {
    }

    public static void main(String[] args) throws Exception {
        var mapper = JsonMapper.builder().build();
        var reader = new ReportTextReader();
        var redactor = new PersonalInfoRedactor();
        int totalValues = 0;
        int totalLeaks = 0;
        for (String arg : args) {
            Path dir = Path.of(arg).toAbsolutePath().normalize();
            int values = 0;
            int leaks = 0;
            Map<String, Integer> redacted = new TreeMap<>();
            List<String> lines = Files.readAllLines(dir.resolve("manifest.jsonl"));
            for (String line : lines) {
                Map<String, Object> row = mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                String text = reader.read(Files.readAllBytes(dir.resolve(row.get("id") + ".pdf")), "report.pdf");
                var redaction = redactor.redact(text);
                redaction.counts().forEach((type, count) -> redacted.merge(type.name(), count, Integer::sum));
                String outbound = squash(redaction.text());
                @SuppressWarnings("unchecked")
                Map<String, List<String>> pii = (Map<String, List<String>>) row.getOrDefault("pii", Map.of());
                for (var entry : pii.entrySet()) {
                    for (String value : entry.getValue()) {
                        values++;
                        if (outbound.contains(squash(value))) {
                            leaks++;
                            System.out.printf("  남음 %s %s: %s%n", row.get("id"), entry.getKey(), value);
                        }
                    }
                }
            }
            System.out.printf("%s: 보고서 %d개, 식별정보 %d개 중 남은 것 %d개 | 지운 종류별 개수 %s%n",
                    dir.getFileName(), lines.size(), values, leaks, redacted);
            totalValues += values;
            totalLeaks += leaks;
        }
        System.out.printf("전체: 식별정보 %d개 중 남은 것 %d개 (잔존율 %.1f%%)%n", totalValues, totalLeaks,
                totalValues == 0 ? 0.0 : 100.0 * totalLeaks / totalValues);
    }

    /** PDF 글자 추출은 공백이 들쭉날쭉하므로 공백을 지우고 비교한다 */
    private static String squash(String text) {
        return text.replaceAll("\\s+", "");
    }
}
