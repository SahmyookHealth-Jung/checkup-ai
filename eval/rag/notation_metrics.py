"""표기 변형 평가의 지표 (ADR 0010 4절). 모델을 부르지 않는 순수 함수.

판정 대상 항목(판정기준표에 있는 17개 항목)마다, 시스템이 그 값을 어떻게 처리했는지를 넷으로 나눈다.
- correct  깨끗한 값(canonical)으로 낸 정답 판정과 같다
- flagged  판정하지 않았지만 "확인 필요"로 알렸다 (사용자가 원본을 확인하게 된다. 안전한 쪽)
- wrong    정답과 다른 판정을 냈다. 더 나쁜 쪽으로 틀리면 falseAlarm, 더 좋은 쪽이면 falseReassurance
- silent   판정도 알림도 없다 (추출에서 빠졌거나 "판정 기준 없음"으로 지나갔다)

정답 판정은 변형 전의 깨끗한 값과 대표 단위로 낸다 (explanation_metrics.judge, 기준표 경계값은 단위 테스트로 확인돼 있다).
정답이 "판정 기준 없음"인 항목(표에 없는 구간)은 판정하지 않는 것이 맞다.
"""
from __future__ import annotations

from collections import Counter

from rag.explanation_metrics import judge

SEVERITY = {"NORMAL_A": 0, "NORMAL_B": 1, "SUSPECT": 2}
OUTCOMES = ("correct", "flagged", "falseAlarm", "falseReassurance", "silent")


def truth_items(criteria: dict, expected: dict, variants: list[dict]) -> list[dict]:
    """정답 문서의 판정 대상 항목마다: 찍힌 표기, 변형 종류, 정답 판정"""
    by_code = {v["code"]: v for v in variants}
    items = []
    for item in expected["items"]:
        code = item["code"]
        if code not in criteria["items"]:
            continue
        variant = by_code.get(code)
        clean = variant["canonicalValue"] if variant else item["value"]
        items.append({"code": code, "kind": variant["kind"] if variant else "none",
                      "ideal": variant["ideal"] if variant else "judge",
                      "printed": item["value"] + (f" {item['unit']}" if item.get("unit") else ""),
                      "truth": judge(criteria, code, clean, expected.get("sex"))})
    return items


def classify(truth: str, judged: str | None, flagged: bool) -> str:
    """judged: 시스템이 낸 판정 (항목이 없으면 None). flagged: 확인 필요 목록에 있는가"""
    decided = judged if judged in SEVERITY else None
    if decided is None:
        if flagged:
            return "flagged"
        return "correct" if truth == "NOT_JUDGED" else "silent"
    if decided == truth:
        return "correct"
    if truth == "NOT_JUDGED":
        return "falseAlarm" if decided != "NORMAL_A" else "falseReassurance"
    return "falseAlarm" if SEVERITY[decided] > SEVERITY[truth] else "falseReassurance"


def assess_report(criteria: dict, expected: dict, variants: list[dict], explanation: dict) -> list[dict]:
    judged = {}
    for j in explanation.get("judgments") or []:
        judged.setdefault(j["code"], j["judgment"])
    flagged = {r.get("code") for r in explanation.get("needsReview") or []}
    return [{**item, "judged": judged.get(item["code"]), "flagged": item["code"] in flagged,
             "outcome": classify(item["truth"], judged.get(item["code"]), item["code"] in flagged)}
            for item in truth_items(criteria, expected, variants)]


def summarize(rows: list[dict]) -> dict:
    """변형 종류별 처리 결과 건수. 위험한 것은 falseReassurance와 silent다"""
    by_kind = {}
    for kind in sorted({r["kind"] for r in rows}):
        counts = Counter(r["outcome"] for r in rows if r["kind"] == kind)
        by_kind[kind] = {"items": sum(counts.values()), **{o: counts.get(o, 0) for o in OUTCOMES}}
    total = Counter(r["outcome"] for r in rows)
    suspect = [r for r in rows if r["truth"] == "SUSPECT"]
    return {"items": len(rows), **{o: total.get(o, 0) for o in OUTCOMES}, "byKind": by_kind,
            # 정답이 질환의심인 항목 중 진료 권고나 확인 필요가 사용자에게 간 건수
            "suspectReached": f"{sum(1 for r in suspect if r['outcome'] in ('correct', 'flagged', 'falseAlarm'))}/{len(suspect)}"}
