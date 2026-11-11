package com.checkupai.report.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceTextTest {

    @Test
    @DisplayName("표 머리 줄만 고른다: 머리말 단어 2개 이상, 숫자 없음, 안내문·문장 제외")
    void picksHeaderLines() {
        String source = """
                   가상의원   종합건강검진   결과   요약
                   검사명        금회        전회        단위        참고치
                   FBS           183         185         mg/dL       <100
                   성명   생년월일   성별   나이   검진일   검진기관
                   ※ 전회   결과는    직전    검진   기록입니다.   이상   소견은    담당    의사와    상담하시기       바랍니다.
                """;

        assertThat(SourceText.headerLines(source)).containsExactly("검사명 금회 전회 단위 참고치");
    }

    @Test
    @DisplayName("줄을 쉼표로 나눈다. 쉼표가 없으면 줄 전체가 한 조각이다")
    void splitsSegmentsByComma() {
        assertThat(SourceText.segments("· 키 173.0cm, 몸무게 68.5kg, 체질량지수 22.9kg/m² (정상A)"))
                .containsExactly("· 키 173.0cm", "몸무게 68.5kg", "체질량지수 22.9kg/m² (정상A)");
        assertThat(SourceText.segments("FBS 183 185 mg/dL <100")).containsExactly("FBS 183 185 mg/dL <100");
    }

    @Test
    @DisplayName("숫자 값은 다른 숫자의 일부를 인정하지 않고, 글자 값은 공백 차이를 무시한다")
    void containsValue() {
        assertThat(SourceText.containsValue("FBS 183 185", "183")).isTrue();
        assertThat(SourceText.containsValue("FBS 183 185", "18")).isFalse();
        assertThat(SourceText.containsValue("혈색소 18.3 g/dL", "18")).isFalse();      // 소수의 앞부분
        assertThat(SourceText.containsValue("알부민 4.9 (3.5~5.2)", "5")).isFalse();   // 참고치 소수의 앞부분
        assertThat(SourceText.containsValue("허리둘레는 82. 체중은", "82")).isTrue();  // 문장 끝 마침표
        assertThat(SourceText.containsValue("Chest PA 비활동성     폐결핵", "비활동성 폐결핵")).isTrue();
    }

    @Test
    @DisplayName("표시가 붙은 숫자 값은 값 속 숫자도 원문에 온전한 숫자로 있어야 한다")
    void containsDecoratedNumber() {
        assertThat(SourceText.containsValue("eGFR 64 L mL/min", "64 L")).isTrue();
        assertThat(SourceText.containsValue("혈색소 9.3▼ g/dL", "9.3▼")).isTrue();
        assertThat(SourceText.containsValue("공복혈당 1,120 mg/dL", "1,120")).isTrue();
        assertThat(SourceText.containsValue("eGFR >90", ">90")).isTrue();
        // 조각 검사만 하면 "64"가 "164" 안에서 통과한다
        assertThat(SourceText.containsValue("신장 164 cm, LDL 100", "64 L")).isFalse();
        assertThat(SourceText.containsValue("혈색소 19.3▼", "9.3▼")).isFalse();
        // 점·쉼표로 이어진 숫자는 한 덩어리로 대조한다 (2026-11-11 Fable 리뷰)
        assertThat(SourceText.containsValue("다음 예약 2026.09.16", "2026.09.16")).isTrue();
        assertThat(SourceText.containsValue("TG 11,120 요단백 1+", "1,120")).isFalse();
        assertThat(SourceText.containsValue("요단백 1+ (30)", "1+ (30)")).isTrue();
    }

    @Test
    @DisplayName("두 줄에 걸친 값은 두 줄을 이어 한 범위로 본다. 한 줄에 온전히 있으면 그 줄만 본다")
    void findsValueWindowsAcrossWrappedLines() {
        var lines = SourceText.lines("""
                흉부방사선   기타  질환   의심(소
                견서  참조)   -   -   질환의심
                공복혈당   112   mg/dL
                """);

        assertThat(SourceText.valueWindows(lines, "기타 질환 의심(소견서 참조)"))
                .containsExactly("흉부방사선 기타 질환 의심(소 견서 참조) - - 질환의심");
        assertThat(SourceText.valueWindows(lines, "112")).containsExactly("공복혈당 112 mg/dL");
        assertThat(SourceText.spansLines("값 12", "3 단위", "123")).as("숫자는 줄바꿈으로 나뉘지 않는다").isFalse();
    }
}
