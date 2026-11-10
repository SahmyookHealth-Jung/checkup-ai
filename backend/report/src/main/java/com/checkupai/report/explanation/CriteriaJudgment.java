package com.checkupai.report.explanation;

import com.checkupai.report.extraction.ItemCode;

/**
 * 한 항목의 기준 판정 결과.
 *
 * @param criterion 판정에 쓴 기준 표의 행 (원문 요약). 기준이 없으면 null
 * @param source 기준의 출처 (문서명과 고시 번호). 가드레일 원칙 6
 */
public record CriteriaJudgment(ItemCode code, String value, Judgment judgment, String criterion, String source) {
}
