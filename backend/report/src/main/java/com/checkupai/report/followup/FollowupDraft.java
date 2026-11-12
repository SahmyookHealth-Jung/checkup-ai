package com.checkupai.report.followup;

import com.checkupai.report.explanation.ExplanationDraft;
import java.util.List;

/**
 * LLM이 내는 후속 질문 답 초안 (구조화 출력, ADR 0011 3절). 처리 구분과 문장·근거 번호만 낸다.
 * 받아들일지와 최종 처리는 코드가 정한다 ({@link FollowupService}).
 *
 * @param decision ANSWER, NO_EVIDENCE, REFUSE_DIAGNOSIS, REFUSE_TREATMENT, MALICIOUS
 * @param uncovered 질문 중 근거에 없어 답하지 않은 부분 (다 답했으면 비움)
 */
public record FollowupDraft(String decision, String uncovered, List<ExplanationDraft.SentenceDraft> sentences) {
}
