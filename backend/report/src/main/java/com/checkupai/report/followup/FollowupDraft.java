package com.checkupai.report.followup;

import java.util.List;

/**
 * LLM이 내는 후속 질문 답 초안 (구조화 출력, ADR 0011 3절). 처리 구분과 문장·근거 번호·근거 구절만 낸다.
 * 받아들일지와 최종 처리는 코드가 정한다 ({@link FollowupService}).
 *
 * @param decision ANSWER, NO_EVIDENCE, REFUSE_DIAGNOSIS, REFUSE_TREATMENT, MALICIOUS
 * @param uncovered 질문 중 근거에 없어 답하지 않은 부분 (다 답했으면 비움)
 */
public record FollowupDraft(String decision, String uncovered, List<SentenceDraft> sentences) {

    /**
     * @param quote 문장을 뒷받침하는 구절. 인용한 근거 문단에서 글자 그대로 베낀 것이어야 한다 (followup-v2,
     *              2026-11-12 Codex 적대적 리뷰: 인용 번호만으로는 비수치 주장이 근거에 있는지 알 수 없다)
     */
    public record SentenceDraft(String text, List<String> sources, String quote) {
    }
}
