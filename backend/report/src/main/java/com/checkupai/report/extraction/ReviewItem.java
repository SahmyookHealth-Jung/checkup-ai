package com.checkupai.report.extraction;

/**
 * 확인 필요 항목 (ADR 0007 1절). 값은 원문에 있지만 생리적으로 가능한 범위 밖이라 결과에 넣지 않은 항목이다.
 * 읽기 오류일 수도, 매우 위험한 수치일 수도 있다. 그래서 버리지 않고 사용자에게 보여 준다.
 * 해설 대상에서는 빼고, 화면에는 고정 문구로 원본 확인과 진료를 권고한다(가드레일 원칙 3).
 *
 * @param reason 결과에 넣지 않은 이유 (예: "가능 범위 밖: 1120")
 */
public record ReviewItem(ItemCode code, String rawName, String value, String reason) {
}
