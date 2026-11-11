package com.checkupai.report.explanation;

import com.checkupai.report.extraction.ItemCode;
import java.util.List;

/**
 * 출처가 붙은 해설 (ADR 0007).
 *
 * @param judgments 추출한 모든 항목의 국가 기준 판정 (화면의 결과 표)
 * @param items 해설한 항목 (정상B·질환의심)
 * @param needsReview 확인 필요 항목과 고정 권고 문구
 * @param citations 해설 문장이 인용한 근거 (번호 순)
 * @param disclaimer 고지문 (코드가 붙인다)
 */
public record ExplanationResult(List<ItemJudgment> judgments, List<ItemExplanation> items,
                                List<ReviewNotice> needsReview, List<Citation> citations, String disclaimer,
                                Metrics metrics) {

    /** @param centerJudgment 결과지에 적힌 판정 (국가 기준 판정과 따로 보여 준다) */
    public record ItemJudgment(ItemCode code, String name, String rawName, String value, String unit,
                               Judgment judgment, String judgmentLabel, String criterion, String centerJudgment) {
    }

    /**
     * @param sentences 검증을 통과한 문장. 비어 있으면 noEvidence
     * @param careAdvice 질환의심 항목의 진료 권고 (코드가 붙인 고정 문구), 그 밖에는 null
     * @param evidenceChunkIds 이 항목에 허락된 근거 청크 id (검색 결과). 조건을 바꿨을 때 근거 목록이 바뀐 항목을 가려내는 데 쓴다
     */
    public record ItemExplanation(ItemCode code, String name, String value, String unit, Judgment judgment,
                                  String judgmentLabel, String criterion, String criterionSource,
                                  List<Sentence> sentences, boolean noEvidence, String careAdvice,
                                  List<String> evidenceChunkIds) {
    }

    /** 문장을 만든 쪽. LLM 문장은 인용·숫자 검증을 거친다. 코드 문구는 판정 결과와 판정기준표 행으로 조립한다 */
    public enum Origin { LLM, CODE }

    /** @param citations 인용 번호 ({@link Citation#number()}) */
    public record Sentence(String text, List<Integer> citations, Origin origin) {
    }

    public record Citation(int number, String chunkId, String title, String publisher, String edition,
                           String section, String url, String license, String text) {
    }

    /**
     * @param reason 확인이 필요한 이유 (예: "가능 범위 밖: 1120", "단위가 다름: mmol/L (기준 단위 mg/dL)")
     */
    public record ReviewNotice(ItemCode code, String rawName, String value, String advice, String reason) {
    }

    /**
     * 해설 검증 측정값 (ADR 0007 9절 측정 2). 5단계 개선의 기준선이다.
     *
     * @param sentencesGenerated LLM이 낸 문장 수
     * @param sentencesKept 인용·숫자 검증을 통과한 문장 수
     * @param invalidCitations 그 항목에 허락되지 않은 근거 번호를 쓴 횟수 (지운다)
     * @param uncitedSentences 유효한 인용이 하나도 없어 버린 문장 수
     * @param ungroundedNumberSentences 결과값·기준·인용 근거에 없는 숫자가 있어 버린 문장 수
     * @param forbiddenHits 진단 단정·치료 지시 표현이 걸린 문장 수 (막지 않고 기록만)
     * @param llmError 해설 생성이 실패했으면 오류 종류
     * @param salvagedItems 출력이 상한에서 잘려 살린 항목 수 (잘리지 않았으면 -1)
     * @param templatedItems 코드 문구로 답한 항목 수 (LLM 문장 수에는 넣지 않는다)
     * @param itemFilter 항목 필터를 켰는지
     */
    public record Metrics(int targets, int targetsWithEvidence, int sentencesGenerated, int sentencesKept,
                          int invalidCitations, int uncitedSentences, int ungroundedNumberSentences,
                          int forbiddenHits, long retrievalMs, long llmMs, long totalMs, String llmError,
                          int salvagedItems, int templatedItems, boolean itemFilter) {
    }
}
