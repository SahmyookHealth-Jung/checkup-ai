package com.checkupai.report.explanation;

import com.checkupai.report.extraction.ItemCode;

/**
 * 한 항목의 기준 판정 결과.
 *
 * @param criterion 판정에 쓴 기준 표의 행 (원문 요약). 기준이 없으면 null
 * @param source 기준의 출처 (문서명과 고시 번호). 가드레일 원칙 6
 * @param note 판정 대상인데 판정하지 못한 이유 (단위가 다름, 알 수 없는 표기 등). 판정했으면 null
 * @param unitNote 단위 표기가 모호한데도 판정할 수 있었을 때 화면에 밝히는 문구 (ADR 0007 보완 2026-11-14). 없으면 null
 */
public record CriteriaJudgment(ItemCode code, String value, Judgment judgment, String criterion, String source,
                               String note, String unitNote) {

    /** 판정기준표에 있는 항목인데 판정하지 못했다. 해설 서비스가 "확인 필요"로 알린다 */
    public boolean unjudgedTarget() {
        return judgment == Judgment.NOT_JUDGED && criterion != null;
    }
}
