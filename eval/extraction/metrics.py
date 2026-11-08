"""추출 평가 지표: 항목 F1 (code + value 일치).

대표 지표로 결정 (2026-11-08). 정밀도와 재현율을 함께 기록한다.
- 정밀도(precision) = 맞게 뽑은 항목 / 뽑은 항목 → 잘못 뽑거나 지어낸 값이 있으면 떨어진다
- 재현율(recall)    = 맞게 뽑은 항목 / 정답 항목 → 빠뜨린 항목이 있으면 떨어진다

매칭 규칙
- OTHER가 아닌 항목은 code로 1:1 매칭한다. 값이 같으면 정답(TP). 다르면 잘못 뽑음(FP)과 빠뜨림(FN)을 하나씩 센다.
- OTHER 항목은 code만으로 구분이 안 되므로 값으로 매칭한다.
- 값 비교: 공백 제거 후 문자열 비교. 둘 다 숫자면 수치로 비교한다("26.0"과 "26"은 같다).
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field


def normalize_value(value) -> str | None:
    if value is None:
        return None
    text = str(value).replace(" ", "").strip()
    try:
        return repr(float(text))
    except ValueError:
        return text


@dataclass
class Score:
    tp: int = 0
    fp: int = 0
    fn: int = 0
    errors: list[dict] = field(default_factory=list)

    def add(self, other: "Score") -> "Score":
        return Score(self.tp + other.tp, self.fp + other.fp, self.fn + other.fn, self.errors + other.errors)

    @property
    def precision(self) -> float:
        return self.tp / (self.tp + self.fp) if self.tp + self.fp else 0.0

    @property
    def recall(self) -> float:
        return self.tp / (self.tp + self.fn) if self.tp + self.fn else 0.0

    @property
    def f1(self) -> float:
        p, r = self.precision, self.recall
        return 2 * p * r / (p + r) if p + r else 0.0

    def summary(self) -> dict:
        return {"tp": self.tp, "fp": self.fp, "fn": self.fn,
                "precision": round(self.precision, 4), "recall": round(self.recall, 4), "f1": round(self.f1, 4)}


def score_document(expected_items: list[dict], predicted_items: list[dict]) -> Score:
    score = Score()

    def error(kind: str, code: str, expected=None, predicted=None) -> None:
        score.errors.append({"type": kind, "code": code, "expected": expected, "predicted": predicted})

    # 1) OTHER가 아닌 항목: code로 매칭
    expected_by_code = {it["code"]: it for it in expected_items if it["code"] != "OTHER"}
    predicted_by_code: dict[str, list[dict]] = defaultdict(list)
    for it in predicted_items:
        if it.get("code") != "OTHER":
            predicted_by_code[it.get("code")].append(it)

    for code, expected in expected_by_code.items():
        candidates = predicted_by_code.pop(code, [])
        if not candidates:
            score.fn += 1
            error("missing", code, expected=expected["value"])
            continue
        target = normalize_value(expected["value"])
        hit = next((c for c in candidates if normalize_value(c.get("value")) == target), None)
        if hit is not None:
            score.tp += 1
            candidates.remove(hit)
        else:
            wrong = candidates.pop(0)
            score.fp += 1
            score.fn += 1
            error("wrong_value", code, expected=expected["value"], predicted=wrong.get("value"))
        for duplicate in candidates:
            score.fp += 1
            error("duplicate", code, predicted=duplicate.get("value"))

    for code, leftovers in predicted_by_code.items():  # 정답에 없는 코드
        for item in leftovers:
            score.fp += 1
            error("extra", code, predicted=item.get("value"))

    # 2) OTHER 항목: 값으로 매칭
    unmatched = [it for it in expected_items if it["code"] == "OTHER"]
    for item in (it for it in predicted_items if it.get("code") == "OTHER"):
        value = normalize_value(item.get("value"))
        hit = next((e for e in unmatched if normalize_value(e["value"]) == value), None)
        if hit is not None:
            score.tp += 1
            unmatched.remove(hit)
        else:
            score.fp += 1
            error("extra", "OTHER", predicted=f"{item.get('rawName')}={item.get('value')}")
    for item in unmatched:
        score.fn += 1
        error("missing", "OTHER", expected=f"{item['rawName']}={item['value']}")

    return score


def score_failed_document(expected_items: list[dict], reason: str) -> Score:
    """API 오류나 JSON 변환 실패: 정답 항목을 모두 빠뜨린 것으로 센다."""
    return Score(fn=len(expected_items), errors=[{"type": "failed", "code": "*", "expected": reason, "predicted": None}])
