package com.checkupai.report.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.checkupai.report.extraction.ExtractionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class DemoSamplesTest {

    private final DemoSamples samples = new DemoSamples(new DemoProperties("없는-폴더"), null, null, null);

    @Test
    @DisplayName("선택 상자 이름: 양식별로 묶고, 결과지 값을 기준표로 판정해 질환의심·경계 항목을 적는다")
    void labelsFromExpected(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("nar-001.pdf"), "");
        Files.writeString(dir.resolve("nar-001.expected.json"), """
                {"examDate": "2026-10-10", "examType": "GENERAL", "sex": "MALE", "age": null, "items": [
                  {"code": "HEIGHT", "rawName": "키", "value": "173.0", "unit": "cm"},
                  {"code": "FASTING_GLUCOSE", "rawName": "공복혈당", "value": "110", "unit": "mg/dL"},
                  {"code": "AST", "rawName": "AST", "value": "125", "unit": "U/L"}]}
                """);
        Files.writeString(dir.resolve("std-002.pdf"), "");   // 정답 파일이 없으면 번호만
        DemoSamples labeled = new DemoSamples(new DemoProperties(dir.toString()), null, null, new ScreeningCriteria());

        assertThat(labeled.samples()).containsExactly(
                new DemoSamples.Sample("nar-001", "목록 + 의사 소견", "1번 · 남 · 질환의심 AST · 경계 공복혈당"),
                new DemoSamples.Sample("std-002", "공단 표준 서식", "2번"));
    }

    private static ExtractionResult extraction() {
        return new ExtractionResult(null, null, null, List.of());
    }

    @Test
    @DisplayName("올린 결과지는 받은 id로 같은 추출 결과를 돌려준다")
    void remembersUpload() {
        ExtractionResult uploaded = extraction();
        String id = samples.remember(uploaded);
        assertThat(id).matches("up-[0-9a-f]{24}");
        assertThat(samples.extraction(id)).isSameAs(uploaded);
    }

    @Test
    @DisplayName("기억하지 않은 업로드 id는 404, 형식이 틀린 id는 400")
    void unknownUpload() {
        assertThatThrownBy(() -> samples.extraction("up-" + "0".repeat(24)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> samples.extraction("up-../x"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    @DisplayName("올린 결과지는 최근 MAX_UPLOADS개만 기억한다")
    void evictsOldest() {
        String first = samples.remember(extraction());
        for (int i = 0; i < DemoSamples.MAX_UPLOADS; i++) {
            samples.remember(extraction());
        }
        assertThatThrownBy(() -> samples.extraction(first)).hasMessageContaining("404");
    }
}
