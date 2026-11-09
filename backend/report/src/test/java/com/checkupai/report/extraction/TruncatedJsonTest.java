package com.checkupai.report.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TruncatedJsonTest {

    @Test
    @DisplayName("속성 이름 중간에서 끊긴 출력을 마지막 완성 항목까지 살린다 (sum-008: 37번째 항목에서 끊김)")
    void closesAfterLastCompleteItem() {
        String truncated = """
                {"examDate":"2026-10-26","examType":"COMPREHENSIVE","sex":"FEMALE","age":42,"items":[\
                {"code":"FASTING_GLUCOSE","rawName":"FBS","value":"183"},\
                {"code":"HEMOGLOBIN","rawName":"Hb","value":"10.4"},\
                {"code":"AST","raw""";

        TruncatedJson.Repair repair = TruncatedJson.closeAfterLastCompleteItem(truncated);

        assertThat(repair.items()).isEqualTo(2);
        assertThat(repair.json()).endsWith("{\"code\":\"HEMOGLOBIN\",\"rawName\":\"Hb\",\"value\":\"10.4\"}]}");
    }

    @Test
    @DisplayName("문자열 속 괄호와 따옴표 이스케이프는 구조로 세지 않는다")
    void ignoresBracesInsideStrings() {
        String truncated = "{\"items\":[{\"rawName\":\"참조) {정상}\",\"value\":\"a\\\"]}\"},{\"rawName\":\"x";

        TruncatedJson.Repair repair = TruncatedJson.closeAfterLastCompleteItem(truncated);

        assertThat(repair.items()).isEqualTo(1);
        assertThat(repair.json()).isEqualTo("{\"items\":[{\"rawName\":\"참조) {정상}\",\"value\":\"a\\\"]}\"}]}");
    }

    @Test
    @DisplayName("배열이 닫힌 출력, 완성된 항목이 없는 출력, items가 없는 출력은 살리지 않는다")
    void returnsNullWhenNothingToSalvage() {
        assertThat(TruncatedJson.closeAfterLastCompleteItem("{\"items\":[{\"value\":\"1\"}]")).isNull();
        assertThat(TruncatedJson.closeAfterLastCompleteItem("{\"items\":[{\"value\":\"1")).isNull();
        assertThat(TruncatedJson.closeAfterLastCompleteItem("{\"examDate\":\"2026")).isNull();
    }

    @Test
    @DisplayName("변환기는 정상 출력은 그대로, 잘린 출력은 살려서, 살릴 수 없는 출력은 원래 예외로 처리한다")
    void converterSalvagesOnlyTruncatedOutput() {
        String complete = "{\"items\":[{\"code\":\"OTHER\",\"rawName\":\"TSH\",\"value\":\"3.88\"}]}";
        String truncated = "{\"items\":[{\"code\":\"OTHER\",\"rawName\":\"TSH\",\"value\":\"3.88\"},{\"code\":\"OT";

        var normal = new SalvagingOutputConverter<>(ItemList.class);
        assertThat(normal.convert(complete).items()).hasSize(1);
        assertThat(normal.salvagedItems()).isEqualTo(-1);

        var salvaging = new SalvagingOutputConverter<>(ItemList.class);
        assertThat(salvaging.convert(truncated).items()).extracting(ReportItem::rawName).containsExactly("TSH");
        assertThat(salvaging.salvagedItems()).isEqualTo(1);

        var broken = new SalvagingOutputConverter<>(ItemList.class);
        assertThatThrownBy(() -> broken.convert("{\"items\":[{\"code\":\"OT")).isInstanceOf(RuntimeException.class);
        assertThat(broken.getJsonSchema()).contains("\"items\"");
    }
}
