package com.checkupai.report.extraction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** 누락 의심 줄을 다시 추출할 때의 응답 형식. */
public record ItemList(

        @JsonProperty(required = true)
        @JsonPropertyDescription("추출할 줄에 있는 이번 검사 항목. 없으면 빈 배열")
        List<ReportItem> items) {
}
