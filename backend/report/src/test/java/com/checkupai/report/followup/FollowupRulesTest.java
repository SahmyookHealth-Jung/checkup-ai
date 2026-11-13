package com.checkupai.report.followup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
        // 시험 몫에서 LLM과 옛 규칙이 모두 놓친 어순
        assertThat(FollowupRules.check("간 수치가 이 정도면 간경화까지 간 건가요?").diagnosis()).isTrue();
        assertThat(FollowupRules.check("혈압 이 정도면 고혈압 확정이에요?").diagnosis()).isTrue();
        // 질환 이름이 들어간 일반 정보 질문은 걸리지 않는다
        assertThat(FollowupRules.check("당뇨병 진단 기준이 뭐예요?").diagnosis()).isFalse();
        assertThat(FollowupRules.check("빈혈이면 어지러운 거예요?").diagnosis()).isFalse();
        assertThat(FollowupRules.check("비만이면 고혈압 위험이 커지나요?").diagnosis()).isFalse();
    }

    @Test
    @DisplayName("답 검사: 진단을 확인해 주는 답만 걸리고, 판정 구간·기준 설명은 걸리지 않는다 (ADR 0014)")
    void answerCheck() {
        assertThat(FollowupRules.answerConfirmsDiagnosis("LDL 193이면 고지혈증이랑 같은 거죠?",
                List.of("네, LDL 130 이상이면 이상지질혈증에 해당하는 기준이 됩니다."))).isTrue();
        assertThat(FollowupRules.answerConfirmsDiagnosis("이거 진단이랑 같은 말이죠?", List.of("맞습니다. 판정 기준상 질환의심 구간입니다."))).isTrue();
        assertThat(FollowupRules.answerConfirmsDiagnosis("혈압이 왜 높을까요?", List.of("고혈압이 맞습니다."))).isTrue();
        assertThat(FollowupRules.answerConfirmsDiagnosis("LDL 193이면 그런 거죠?",
                List.of("네, 기준을 넘었습니다.", "이상지질혈증에 해당하는 수치입니다."))).isTrue();  // 질환이 둘째 문장에 있다

        assertThat(FollowupRules.answerConfirmsDiagnosis("공복혈당이 질환의심 구간인가요?",
                List.of("네, 공복혈당 136은 질환의심 구간(126 이상)에 해당합니다."))).isFalse();
        assertThat(FollowupRules.answerConfirmsDiagnosis("LDL은 몇부터 높은 거예요?",
                List.of("LDL 160 이상은 이상지질혈증 기준에 해당합니다."))).isFalse();
        assertThat(FollowupRules.answerConfirmsDiagnosis("당뇨 관리는 어떻게 해요?",
                List.of("예를 들어 식사 조절과 운동이 도움이 됩니다."))).isFalse();
        assertThat(FollowupRules.answerConfirmsDiagnosis("아무거나", List.of())).isFalse();
    }
}
