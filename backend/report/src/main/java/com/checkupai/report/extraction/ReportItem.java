package com.checkupai.report.extraction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 결과지의 검사 항목 한 줄.
 *
 * <p>{@code value}는 원문 문자열 그대로 담는다. 숫자 변환과 단위 환산은 코드가 한다.
 * 이렇게 해야 추출한 값이 원문에 실제로 있는지 대조할 수 있다 (ADR 0003).
 */
public record ReportItem(

        @JsonProperty(required = true)
        @JsonPropertyDescription("표준 코드. 목록에 없는 검사는 OTHER")
        ItemCode code,

        @JsonProperty(required = true)
        @JsonPropertyDescription("결과지에 적힌 항목명 그대로")
        String rawName,

        @JsonProperty(required = true)
        @JsonPropertyDescription("이번 검사 결과값. 결과지에 적힌 글자 그대로 (예: \"112\", \"음성\", \"1+\"). 전회 결과는 넣지 않는다")
        String value,

        @JsonPropertyDescription("결과지에 적힌 단위 그대로. 없으면 null")
        String unit,

        @JsonPropertyDescription("결과지에 적힌 참고치(정상범위) 그대로. 없으면 null")
        String referenceRaw,

        @JsonPropertyDescription("결과지에 적힌 이 항목의 판정 그대로 (예: \"정상A\", \"정상B(경계)\", \"질환의심\"). 없으면 null")
        String centerJudgment) {
}
