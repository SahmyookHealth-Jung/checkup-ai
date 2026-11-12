package com.checkupai.report.followup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FollowupRulesTest {

    @Test
    @DisplayName("약·치료 질문을 찾는다")
    void findsTreatment() {
        assertThat(FollowupRules.check("혈압약 끊어도 되나요?").treatment()).isTrue();
        assertThat(FollowupRules.check("철분제 하루에 몇 알 먹으면 돼요?").treatment()).isTrue();
        assertThat(FollowupRules.check("약 먹어야 하나요?").treatment()).isTrue();
        assertThat(FollowupRules.check("영양제 알려줄래?").treatment()).isTrue();
    }

    @Test
    @DisplayName("'약간', '요약', '약양성'처럼 약이 들어간 다른 말에는 걸리지 않는다")
    void ignoresOtherWordsWithYak() {
        assertThat(FollowupRules.check("요단백이 약양성이면 안 좋은 건가요?").treatment()).isFalse();
        assertThat(FollowupRules.check("혈압이 약간 높은데 괜찮아요?").treatment()).isFalse();
        assertThat(FollowupRules.check("결과를 요약해 주세요").treatment()).isFalse();
    }

    @Test
    @DisplayName("나에게 병이 있는지 단정해 달라는 질문을 찾고, 일반 정보 질문에는 걸리지 않는다")
    void findsDiagnosisRequests() {
        assertThat(FollowupRules.check("무슨 병이 있는 건가요?").diagnosis()).isTrue();
        assertThat(FollowupRules.check("저 당뇨병인가요?").diagnosis()).isTrue();
        assertThat(FollowupRules.check("만성콩팥병 몇 기예요?").diagnosis()).isTrue();
        assertThat(FollowupRules.check("BMI가 높으면 무슨 질환이 의심되나요?").diagnosis()).isFalse();
    }
}
