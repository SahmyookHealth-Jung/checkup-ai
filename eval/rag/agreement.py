"""일치도 계산 (ADR 0009 4절). 모델을 부르지 않는 순수 함수.

- cohen_kappa: 두 평가자(사람 vs 채점기)의 명목 라벨 일치도. 우연히 맞을 확률을 뺀 값이다.
  한쪽이 한 라벨만 쓰면 기대 일치가 1이 되어 정의되지 않는다(None). 그때는 단순 일치율만 본다.
- majority: 채점기를 여러 번 돌린 라벨의 다수결. 동률이면 더 엄격한 라벨을 고른다 (통과를 후하게 주지 않게).
- self_consistency: 같은 문장을 여러 번 채점했을 때 모든 회차가 같은 라벨을 낸 비율.
"""
from __future__ import annotations

from collections import Counter

# 동률일 때 고를 순서 (엄격한 쪽 먼저)
STRICTNESS = {
    "support": ["unsupported", "partial", "supported"],
    "band": ["wrong", "not_stated", "correct"],
    "treatment": ["present", "none"],
}


def cohen_kappa(a: list[str], b: list[str]) -> float | None:
    assert len(a) == len(b) and a, "같은 길이의 라벨 목록이 필요하다"
    n = len(a)
    observed = sum(1 for x, y in zip(a, b) if x == y) / n
    ca, cb = Counter(a), Counter(b)
    expected = sum(ca[label] * cb[label] for label in set(a) | set(b)) / (n * n)
    if expected == 1:
        return None
    return round((observed - expected) / (1 - expected), 3)


def binary(labels: list[str], positive: str = "supported") -> list[str]:
    return ["pass" if label == positive else "fail" for label in labels]


def majority(labels: list[str], axis: str) -> str:
    counts = Counter(labels)
    best = max(counts.values())
    return next(label for label in STRICTNESS[axis] if counts.get(label) == best)


def self_consistency(repeats_per_item: list[list[str]]) -> float | None:
    multi = [labels for labels in repeats_per_item if len(labels) >= 2]
    if not multi:
        return None
    return round(sum(1 for labels in multi if len(set(labels)) == 1) / len(multi), 3)


def confusion(human: list[str], judge: list[str], order: list[str]) -> dict:
    """사람 라벨(행) x 채점기 라벨(열) 건수"""
    return {h: {j: sum(1 for x, y in zip(human, judge) if x == h and y == j) for j in order} for h in order}
