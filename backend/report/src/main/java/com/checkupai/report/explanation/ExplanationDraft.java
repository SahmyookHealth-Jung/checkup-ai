package com.checkupai.report.explanation;

import java.util.List;

/**
 * LLM이 내는 해설 초안 (구조화 출력). 항목마다 문장과 그 문장의 근거 번호(S1 등)만 낸다.
 * 받아들일지는 {@link ExplanationValidator}가 정한다 (ADR 0007 4절).
 */
public record ExplanationDraft(List<ItemDraft> items) {

    public record ItemDraft(String itemCode, List<SentenceDraft> sentences) {
    }

    public record SentenceDraft(String text, List<String> sources) {
    }
}
