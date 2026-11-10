package com.checkupai.report.explanation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 해설 문장을 코드로 검증한다 (ADR 0007 4절, 추출 검증 ADR 0004와 같은 원칙: LLM 출력은 후보일 뿐이다).
 * <ol>
 *   <li>인용: 그 항목에 허락된 근거 번호만 남긴다. 유효한 인용이 없는 문장은 버린다 (가드레일 원칙 2)</li>
 *   <li>숫자: 문장 속 숫자는 결과값·판정 기준·인용한 근거 문단에 있어야 한다. 없으면 버린다 (지어낸 수치 차단)</li>
 *   <li>금지 표현: 진단 단정·치료 지시는 세어서 기록한다 (4단계는 막지 않는다)</li>
 * </ol>
 */
final class ExplanationValidator {

    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])\\d+(?:\\.\\d+)?");

    /** 검증을 통과한 문장과 그 문장이 인용한 근거 번호(S1 등) */
    record CheckedSentence(String text, List<String> sources) {
    }

    record Outcome(List<CheckedSentence> kept, int generated, int invalidCitations, int uncited,
                   int ungroundedNumbers, int forbiddenHits) {
    }

    /**
     * @param allowedSources 이 항목에 쓸 수 있는 근거 번호 → 근거 문단
     * @param factText 결과값과 판정 기준 (여기 있는 숫자는 쓸 수 있다)
     */
    Outcome check(List<ExplanationDraft.SentenceDraft> sentences, Map<String, String> allowedSources, String factText) {
        List<CheckedSentence> kept = new ArrayList<>();
        int invalidCitations = 0;
        int uncited = 0;
        int ungrounded = 0;
        int forbidden = 0;
        List<ExplanationDraft.SentenceDraft> drafts = sentences == null ? List.of() : sentences;
        for (ExplanationDraft.SentenceDraft draft : drafts) {
            if (draft == null || draft.text() == null || draft.text().isBlank()) {
                continue;
            }
            Set<String> valid = new LinkedHashSet<>();
            for (String source : draft.sources() == null ? List.<String>of() : draft.sources()) {
                String label = source == null ? "" : source.strip().replaceAll("[\\[\\]]", "");
                if (allowedSources.containsKey(label)) {
                    valid.add(label);
                } else {
                    invalidCitations++;
                }
            }
            if (ExplanationGuardrails.forbiddenHits(draft.text()) > 0) {
                forbidden++;
            }
            if (valid.isEmpty()) {
                uncited++;
                continue;
            }
            StringBuilder grounds = new StringBuilder(factText == null ? "" : factText);
            valid.forEach(label -> grounds.append('\n').append(allowedSources.get(label)));
            if (!numbersOf(grounds.toString()).containsAll(numbersOf(draft.text()))) {
                ungrounded++;
                continue;
            }
            kept.add(new CheckedSentence(draft.text().strip(), List.copyOf(valid)));
        }
        return new Outcome(kept, drafts.size(), invalidCitations, uncited, ungrounded, forbidden);
    }

    /** 숫자를 값으로 모은다 ("126"과 "126.0"은 같다) */
    static Set<String> numbersOf(String text) {
        Set<String> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(new BigDecimal(matcher.group()).stripTrailingZeros().toPlainString());
        }
        return numbers;
    }
}
