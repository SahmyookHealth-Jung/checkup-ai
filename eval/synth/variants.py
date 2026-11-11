"""결과지 값·단위의 표기 변형 (ADR 0010 4절, 판정 코드 보완의 평가 데이터).

지금까지의 합성 결과지는 값이 깨끗했다. 숫자 항목은 숫자만, 단위는 항목마다 한 가지, 흉부방사선 값은 3종, 요단백은 4종이다.
그래서 판정 코드가 실제 표기에서 틀리는 것(2026-11-11 전체 검토)을 평가가 잡지 못했다.
여기서는 깨끗한 값을 실제 결과지에서 볼 법한 표기로 바꾸고, 원래의 깨끗한 값(canonical)을 정답으로 남긴다.

- 정답 판정은 깨끗한 값으로 낸다. 표기가 달라도 뜻이 같으면 판정도 같아야 한다.
- ideal은 바람직한 처리다. judge: 깨끗한 값과 같은 판정을 낸다. review: 판정하지 않고 "확인 필요"로 알린다
  (단위가 다르거나 부등호라 실제 값을 알 수 없는 경우. 환산은 하지 않는다).
- 어떤 표기를 넣을지는 판정 코드를 고치기 전에 정했다. 결과를 보고 이 목록을 고치면 처음 보는 표기가 아니다.
"""
from __future__ import annotations

import random
from dataclasses import replace

from .sampling import Measured

VARIANT_RATE = 0.55
LOW_IS_ABNORMAL = {"HEMOGLOBIN", "HDL_CHOLESTEROL", "EGFR"}
NUMERIC = {"HEMOGLOBIN", "FASTING_GLUCOSE", "TOTAL_CHOLESTEROL", "HDL_CHOLESTEROL", "TRIGLYCERIDES", "LDL_CHOLESTEROL",
           "SERUM_CREATININE", "EGFR", "AST", "ALT", "GAMMA_GTP"}

# 같은 단위의 다른 표기 (판정해야 한다). IU/L은 U/L과 수치가 같다
UNIT_NOTATIONS = {
    "mg/dL": ["mg/dl", "㎎/dL", "mg/㎗"],
    "g/dL": ["g/dl", "g/㎗"],
    "U/L": ["IU/L", "U/l"],
    "mL/min/1.73m²": ["mL/min/1.73m2", "ml/min/1.73㎡"],
}
# 다른 단위 (환산 계수, 소수 자릿수). 판정하지 않고 확인 필요로 알려야 한다
OTHER_UNITS = {
    "FASTING_GLUCOSE": ("mmol/L", 1 / 18.016, 1),
    "TOTAL_CHOLESTEROL": ("mmol/L", 1 / 38.67, 2),
    "HDL_CHOLESTEROL": ("mmol/L", 1 / 38.67, 2),
    "LDL_CHOLESTEROL": ("mmol/L", 1 / 38.67, 2),
    "TRIGLYCERIDES": ("mmol/L", 1 / 88.57, 2),
    "SERUM_CREATININE": ("μmol/L", 88.4, 0),
    "HEMOGLOBIN": ("g/L", 10, 0),
}
TEXT_VARIANTS = {
    # 구간 → (깨끗한 값, 변형 표기와 바람직한 처리)
    "URINE_PROTEIN": {
        "normal": ("음성", [("음성(-)", "judge"), ("Negative", "judge"), ("-", "judge")]),
        "borderline": ("약양성(±)", [("±", "judge"), ("+/-", "judge"), ("trace", "judge")]),
        "abnormal": ("양성(1+)", [("1+", "judge"), ("+1", "judge"), ("양성(+)", "judge"), ("2+", "judge")]),
    },
    "CHEST_XRAY": {
        "normal": ("정상", [("정상(특이소견 없음)", "judge"), ("특이소견 없음", "judge"), ("정상A", "judge"),
                          ("이상 소견 없음", "judge")]),
        "borderline": ("비활동성 폐결핵", [("비활동성 폐결핵(추적 관찰)", "judge"), ("비활동성 결핵 흔적", "review")]),
        "abnormal": ("기타 질환 의심(소견서 참조)", [("폐결절 의심", "judge"), ("활동성 폐결핵 의심", "judge"),
                                               ("심비대 소견", "review")]),
    },
}


def apply_variants(rng: random.Random, items: list[Measured]) -> tuple[list[Measured], list[dict]]:
    """항목마다 VARIANT_RATE 확률로 표기를 바꾼다. 바꾼 항목의 정답(깨끗한 값)을 함께 돌려준다"""
    changed, truth = [], []
    for m in items:
        variant = _pick(rng, m) if rng.random() < VARIANT_RATE else None
        if variant is None:
            changed.append(m)
            continue
        new, kind, ideal = variant
        changed.append(new)
        truth.append({"code": m.code, "kind": kind, "ideal": ideal, "printedValue": new.value, "printedUnit": new.unit,
                      "canonicalValue": m.value, "canonicalUnit": m.unit})
    return changed, truth


def _pick(rng: random.Random, m: Measured) -> tuple[Measured, str, str] | None:
    if m.code in TEXT_VARIANTS:
        _, variants = TEXT_VARIANTS[m.code][m.band]
        value, ideal = rng.choice(variants)
        return replace(m, value=value), "text_variant", ideal
    if m.code not in NUMERIC:
        return None
    kinds = ["unit_notation"]
    if m.band in ("borderline", "abnormal"):
        kinds.append("flag")
    if m.code in OTHER_UNITS:
        kinds.append("unit_other")
    if m.code == "EGFR" and m.band == "normal" and float(m.value) >= 90:
        kinds.append("inequality")
    kind = rng.choice(kinds)
    if kind == "unit_notation":
        return replace(m, unit=rng.choice(UNIT_NOTATIONS[m.unit])), kind, "judge"
    if kind == "flag":
        mark, arrow = ("L", "▼") if m.code in LOW_IS_ABNORMAL else ("H", "▲")
        return replace(m, value=rng.choice([f"{m.value} {mark}", f"{mark} {m.value}", f"{m.value}{arrow}"])), kind, "judge"
    if kind == "unit_other":
        unit, factor, decimals = OTHER_UNITS[m.code]
        return replace(m, value=f"{float(m.value) * factor:.{decimals}f}", unit=unit, reference=None), kind, "review"
    return replace(m, value=">90"), kind, "review"
