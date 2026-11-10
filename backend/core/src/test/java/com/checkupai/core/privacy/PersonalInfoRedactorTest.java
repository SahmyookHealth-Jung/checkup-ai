package com.checkupai.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PersonalInfoRedactorTest {

    private final PersonalInfoRedactor redactor = new PersonalInfoRedactor();

    @Test
    @DisplayName("라벨 뒤의 수검번호·이름은 지우고 성별·나이·검진일은 남긴다 (요약표형)")
    void redactsLabeledRecordNumberAndName() {
        var result = redactor.redact("수검번호: SYN-2026-6245    이름: 최*준 (F/42)    검사일: 2026.10.26");

        assertThat(result.text()).isEqualTo("수검번호: [수검번호]    이름: [이름] (F/42)    검사일: 2026.10.26");
        assertThat(result.counts()).containsOnly(entry(PersonalInfoType.RECORD_NUMBER, 1), entry(PersonalInfoType.NAME, 1));
    }

    @Test
    @DisplayName("표에서 라벨과 값이 다른 줄이어도 가린 이름·가린 생년월일은 지운다 (표준 서식형)")
    void redactsMaskedNameAndBirthDateInTableRow() {
        var result = redactor.redact("""
                성명 | 생년월일 | 성별 | 나이 | 검진일 | 검진기관
                신*민 | 1965-**-** | 남 | 만 60세 | 2026-07-19 | 가상건강검진센터""");

        assertThat(result.text()).isEqualTo("""
                성명 | 생년월일 | 성별 | 나이 | 검진일 | 검진기관
                [이름] | [생년월일] | 남 | 만 60세 | 2026-07-19 | 가상건강검진센터""");
        // PDF 글자 추출에서는 표 구분선이 사라진다. 머리 줄의 "생년월일"을 이름으로 지우면 안 된다
        assertThat(redactor.redact("성명 생년월일 성별 나이 검진일 검진기관\n신*민 1965-**-** 남 만 60세").text())
                .isEqualTo("성명 생년월일 성별 나이 검진일 검진기관\n[이름] [생년월일] 남 만 60세");
    }

    @Test
    @DisplayName("호칭 앞의 이름과 '생' 앞의 생년월일을 지운다 (서술형·안내문형)")
    void redactsNameBeforeHonorific() {
        assertThat(redactor.redact("조*준 님 (남성, 1989-**-** 생)").text()).isEqualTo("[이름] 님 (남성, [생년월일] 생)");
        assertThat(redactor.redact("홍길동 님, 결과를 안내드립니다").text()).isEqualTo("[이름] 님, 결과를 안내드립니다");
        assertThat(redactor.redact("담당 의사 선생님과 상담하세요").text()).isEqualTo("담당 의사 선생님과 상담하세요");
    }

    @Test
    @DisplayName("전화번호·대표번호·도로명 주소를 지우고, 날짜·시각·수치 범위는 남긴다 (검사실형·추이표형·안내문형)")
    void redactsPhoneAndAddressButKeepsNumbers() {
        assertThat(redactor.redact("등록번호: SYN-50520    성별/나이: 여/35    채혈일: 2026/09/16").text())
                .isEqualTo("등록번호: [수검번호]    성별/나이: 여/35    채혈일: 2026/09/16");
        assertThat(redactor.redact("문의: 진단검사의학과 000-0000-0000").text()).isEqualTo("문의: 진단검사의학과 [전화번호]");
        assertThat(redactor.redact("고객번호 C-123456 / 성별 남 / 나이 52세 / 검진일 2026. 7. 14.").text())
                .isEqualTo("고객번호 [수검번호] / 성별 남 / 나이 52세 / 검진일 2026. 7. 14.");
        assertThat(redactor.redact("상담 문의 1588-0000 (평일 09:00~18:00)").text())
                .isEqualTo("상담 문의 [전화번호] (평일 09:00~18:00)");
        assertThat(redactor.redact("가상건강검진센터 · 가상시    가상구   가상로  123,  4층 · 1 / 2쪽").text())
                .isEqualTo("가상건강검진센터 · [주소] · 1 / 2쪽");
    }

    @Test
    @DisplayName("주민등록번호는 뒷자리를 가렸어도 지운다")
    void redactsResidentNumber() {
        assertThat(redactor.redact("주민등록번호 900101-1234567, 보호자 800202-2******").text())
                .isEqualTo("주민등록번호 [주민등록번호], 보호자 [주민등록번호]");
    }

    @Test
    @DisplayName("검사 결과·참고치·날짜·지침 문장은 그대로 둔다 (지운 것이 없으면 개수도 비어 있다)")
    void keepsResultsUntouched() {
        String text = """
                공복혈당 | 183 | mg/dL | 100 미만
                혈압 | 120/80 | mmHg | 120/80 미만
                알부민 4.9 g/dL (기준 3.5~5.2 / 지난 검진 4.7)
                혈소판 150-450 10^3/μL, 검진일 2026-10-26
                이름, 주민등록번호, 생년월일, 주소, 연락처, 수검번호는 옮기지 않는다.""";

        var result = redactor.redact(text);

        assertThat(result.text()).isEqualTo(text);
        assertThat(result.counts()).isEmpty();
        assertThat(result.total()).isZero();
    }
}
