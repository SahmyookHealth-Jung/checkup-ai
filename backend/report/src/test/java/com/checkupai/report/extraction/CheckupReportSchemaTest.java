package com.checkupai.report.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.json.JsonMapper;

class CheckupReportSchemaTest {

    private final BeanOutputConverter<CheckupReport> converter = new BeanOutputConverter<>(CheckupReport.class);

    @Test
    @DisplayName("LLM에 주는 JSON 스키마에 표준 코드 enum과 필드 설명이 들어간다")
    void schemaContainsItemCodesAndDescriptions() {
        String schema = converter.getJsonSchema();

        assertThat(schema).contains("FASTING_GLUCOSE", "GAMMA_GTP", "OTHER");
        assertThat(schema).contains("결과지에 적힌 글자 그대로");
    }

    @Test
    @DisplayName("스키마에 개인식별정보 필드가 없다")
    void schemaHasNoPersonalIdentifierFields() {
        String schema = converter.getJsonSchema().toLowerCase();

        assertThat(schema).doesNotContain("\"name\"", "birth", "residentnumber", "address", "phone");
    }

    @Test
    @DisplayName("LLM 응답 JSON을 CheckupReport로 변환한다")
    void convertsLlmJsonToRecord() {
        String json = """
                {
                  "examDate": "2026-10-14",
                  "examType": "GENERAL",
                  "sex": "MALE",
                  "age": 43,
                  "items": [
                    {"code": "FASTING_GLUCOSE", "rawName": "식전혈당", "value": "112",
                     "unit": "mg/dL", "referenceRaw": "70~99", "centerJudgment": "정상B(경계)"},
                    {"code": "URINE_PROTEIN", "rawName": "요단백", "value": "음성",
                     "unit": null, "referenceRaw": "음성", "centerJudgment": "정상A"}
                  ]
                }
                """;

        CheckupReport report = converter.convert(json);

        assertThat(report.examDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(report.sex()).isEqualTo(CheckupReport.Sex.MALE);
        assertThat(report.items()).extracting(ReportItem::code)
                .containsExactly(ItemCode.FASTING_GLUCOSE, ItemCode.URINE_PROTEIN);
        assertThat(report.items().getFirst().value()).isEqualTo("112");
    }

    @Test
    @DisplayName("JSON으로 직렬화했다가 되돌려도 같은 값이다")
    void roundTripsThroughJson() {
        CheckupReport original = new CheckupReport(
                LocalDate.of(2026, 10, 14), CheckupReport.ExamType.GENERAL, CheckupReport.Sex.FEMALE, 51,
                List.of(new ReportItem(ItemCode.OTHER, "요산", "7.9", "mg/dL", "3.5~7.2", null)));
        JsonMapper mapper = JsonMapper.builder().build();

        CheckupReport restored = mapper.readValue(mapper.writeValueAsString(original), CheckupReport.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("프롬프트용 코드 표에 모든 코드가 한 줄씩 들어간다")
    void promptTableListsEveryCode() {
        assertThat(ItemCode.promptTable().lines()).hasSize(ItemCode.values().length);
    }
}
