"""수검자와 검사 값을 무작위로 만든다. 실제 수검자 데이터는 쓰지 않는다.

값 구간은 item_spec.yaml의 임시값이며, 추출 평가용 문서를 만드는 데만 쓴다.
"""
from __future__ import annotations

import random
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

import yaml

SPEC_PATH = Path(__file__).with_name("item_spec.yaml")
BANDS = ("normal", "borderline", "abnormal")
BAND_WEIGHTS = {"normal": 0.6, "borderline": 0.25, "abnormal": 0.15}

# 결과지에 찍히는 순서
CORE_ORDER = [
    "HEIGHT", "WEIGHT", "WAIST_CIRCUMFERENCE", "BMI", "SYSTOLIC_BP", "DIASTOLIC_BP",
    "VISION_LEFT", "VISION_RIGHT", "HEARING_LEFT", "HEARING_RIGHT",
    "URINE_PROTEIN", "HEMOGLOBIN", "FASTING_GLUCOSE",
    "TOTAL_CHOLESTEROL", "HDL_CHOLESTEROL", "TRIGLYCERIDES", "LDL_CHOLESTEROL",
    "SERUM_CREATININE", "EGFR", "AST", "ALT", "GAMMA_GTP", "CHEST_XRAY",
]
LIPIDS = {"TOTAL_CHOLESTEROL", "HDL_CHOLESTEROL", "TRIGLYCERIDES", "LDL_CHOLESTEROL"}
SENSES = {"VISION_LEFT", "VISION_RIGHT", "HEARING_LEFT", "HEARING_RIGHT"}

SURNAMES = "김이박최정강조윤장임한오서신권황안송"
GIVEN = "수영민진현우희호경숙준서연지훈"


@dataclass(frozen=True)
class Subject:
    sex: str            # MALE / FEMALE
    age: int
    birth_year: int
    exam_date: date
    masked_name: str    # 예: 김*수 (가짜 이름)


@dataclass(frozen=True)
class Measured:
    """한 항목의 이번 결과. previous는 '전회 결과' 방해 요소로만 쓴다."""
    code: str                      # ItemCode 이름 (추가 검사는 OTHER)
    names: tuple[str, str, str]    # 표준명, 영문 약어, 일상어
    value: str
    previous: str
    unit: str | None
    reference: str | None          # 표준 표기 (예: "100 미만")
    band: str | None               # normal/borderline/abnormal. 판정이 없는 항목은 None


def load_spec(path: Path = SPEC_PATH) -> dict:
    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f)


def sample_subject(rng: random.Random) -> Subject:
    sex = rng.choice(["MALE", "FEMALE"])
    age = rng.randint(30, 75)
    exam_date = date(2026, 3, 2) + timedelta(days=rng.randint(0, 240))
    birth_year = exam_date.year - age - rng.choice([0, 1])
    masked_name = f"{rng.choice(SURNAMES)}*{rng.choice(GIVEN)}"
    return Subject(sex, age, birth_year, exam_date, masked_name)


def sample_items(rng: random.Random, spec: dict, subject: Subject, *,
                 include_lipids: bool, include_senses: bool, n_other: int) -> list[Measured]:
    items = spec["items"]
    chosen: dict[str, Measured] = {}

    for code in CORE_ORDER:
        if code in LIPIDS and not include_lipids:
            continue
        if code in SENSES and not include_senses:
            continue
        item = items[code]
        if code == "WEIGHT":
            continue  # BMI를 정한 뒤 계산한다
        if code == "DIASTOLIC_BP":
            band = chosen["SYSTOLIC_BP"].band  # 혈압 두 값은 같은 구간을 쓴다
        elif "linked_to" in item:
            band = chosen[item["linked_to"]].band
        else:
            band = None
        chosen[code] = _sample_one(rng, code, item, subject.sex, band)

    chosen["WEIGHT"] = _weight(rng, items["WEIGHT"], chosen["HEIGHT"], chosen["BMI"])
    ordered = [chosen[c] for c in CORE_ORDER if c in chosen]
    ordered += [_sample_other(rng, o) for o in rng.sample(spec["other_items"], n_other)]
    return ordered


def _sample_one(rng: random.Random, code: str, item: dict, sex: str, band: str | None) -> Measured:
    names = tuple(item["names"])
    reference = _by_sex(item.get("reference"), sex)

    if "choices" in item:  # 시력: 판정 없음
        value = rng.choice(item["choices"])
        previous = rng.choice([c for c in item["choices"] if c != value])
        return Measured(code, names, value, previous, item.get("unit"), reference, None)

    if "text_bands" in item:
        text_bands = item["text_bands"]
        band = band if band in text_bands else _pick_band(rng, text_bands)
        value = rng.choice(text_bands[band])
        every = [v for vs in text_bands.values() for v in vs]
        previous = rng.choice(every)
        return Measured(code, names, value, previous, item.get("unit"), reference, band)

    bands = _by_sex(item["bands"], sex)
    band = band if band in bands else _pick_band(rng, bands)
    low, high = bands[band]
    decimals = item["decimals"]
    number = round(rng.uniform(low, high), decimals)
    judged = band if len(bands) > 1 else None  # 구간이 하나뿐인 항목(신장)은 판정이 없다
    return Measured(code, names, _fmt(number, decimals), _previous(rng, number, decimals),
                    item.get("unit"), reference, judged)


def _weight(rng: random.Random, item: dict, height: Measured, bmi: Measured) -> Measured:
    meters = float(height.value) / 100
    weight = round(float(bmi.value) * meters * meters, item["decimals"])
    return Measured("WEIGHT", tuple(item["names"]), _fmt(weight, item["decimals"]),
                    _previous(rng, weight, item["decimals"]), item["unit"], None, None)


def _sample_other(rng: random.Random, other: dict) -> Measured:
    low, high = other["range"]
    number = round(rng.uniform(low, high), other["decimals"])
    names = (other["name"], other["abbr"], other["name"])
    return Measured("OTHER", names, _fmt(number, other["decimals"]),
                    _previous(rng, number, other["decimals"]), other["unit"], other["reference"], None)


def _by_sex(value, sex: str):
    if isinstance(value, dict) and ("male" in value or "female" in value):
        return value["male" if sex == "MALE" else "female"]
    return value


def _pick_band(rng: random.Random, bands: dict) -> str:
    names = [b for b in BANDS if b in bands]
    return rng.choices(names, weights=[BAND_WEIGHTS[b] for b in names])[0]


def _fmt(number: float, decimals: int) -> str:
    return f"{number:.{decimals}f}"


def _previous(rng: random.Random, number: float, decimals: int) -> str:
    """전회 값: 이번 값과 5~15% 다르게 만든다 (같은 값이면 방해 요소가 안 된다)."""
    for _ in range(20):
        candidate = round(number * rng.uniform(0.85, 1.15), decimals)
        if candidate != number:
            return _fmt(candidate, decimals)
    return _fmt(number + 10 ** -decimals, decimals)
