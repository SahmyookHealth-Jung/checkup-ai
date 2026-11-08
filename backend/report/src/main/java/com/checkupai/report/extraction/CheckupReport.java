package com.checkupai.report.extraction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.time.LocalDate;
import java.util.List;

/**
 * 결과지에서 추출한 구조화 결과.
 *
 * <p>이름, 주민번호, 생년월일, 주소, 연락처 같은 개인식별정보는 필드 자체가 없다.
 * 스키마 차원에서 추출되지 않게 막는다 (ADR 0003).
 */
public record CheckupReport(

        @JsonPropertyDescription("검진일 (YYYY-MM-DD). 없으면 null")
        LocalDate examDate,

        @JsonPropertyDescription("검진 종류. 국가 일반건강검진이면 GENERAL, 종합검진이면 COMPREHENSIVE, 알 수 없으면 UNKNOWN")
        ExamType examType,

        @JsonPropertyDescription("성별. 알 수 없으면 UNKNOWN")
        Sex sex,

        @JsonPropertyDescription("결과지에 적힌 나이(만 나이 숫자). 없으면 null. 생년월일에서 계산하지 않는다")
        Integer age,

        @JsonProperty(required = true)
        @JsonPropertyDescription("이번 검사 항목 목록")
        List<ReportItem> items) {

    public enum ExamType { GENERAL, COMPREHENSIVE, UNKNOWN }

    public enum Sex { MALE, FEMALE, UNKNOWN }
}
