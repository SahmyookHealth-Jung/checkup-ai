"""채점기 검증용 오류 심은 세트 (ADR 0010 5절, ADR 0009 "채점기 검증"). 모델을 부르지 않는 순수 함수와 치환 목록.

보정셋에는 판정 구간 "틀림"과 치료·약 "있음"이 0개라, 채점기가 오류를 잡는지는 잰 적이 없다 (2026-11-11 전체 검토).
사람 라벨과 채점기 3회가 모두 통과시킨 문장(바탕 문장)에 오류를 하나씩 심고, 채점기가 그 오류를 잡는지 센다.

- 문장 하나에 오류는 하나다. 놓쳤을 때 원인을 가릴 수 있다.
- 글자를 바꾸는 오류는 아래 치환 목록(TEXT_EDITS)이 전부다. 찾을 글자가 바탕 문장에 정확히 한 번 있어야 한다.
  목록은 Claude가 바탕 문장을 읽고 썼다. 사용자가 검토 화면에서 141문장을 확인했다 (2026-11-11, 고친 문장 없음).
  목록이나 문구를 바꾸면 세트 이름을 올리고(seeded-v2) 다시 확인받는다.
- 기준 수치를 바꿀 때는 같은 기준표 행에 있는 다른 구간의 숫자를 쓴다.
  해설 검증기의 숫자 검사(문장 속 숫자가 근거에 있는가)를 통과하는 이웃 구간 오류다.
- 덧붙이는 문장은 정해 둔 문구를 차례로 돌려 쓴다. 덧붙일 바탕 문장은 시드를 고정해 뽑는다.
- 채점기는 이 파일의 기대 판정을 보지 않는다. 실행기는 문장, 항목 정보, 인용 근거만 채점기에 준다.

한계: 심은 오류는 자연스러운 오류보다 쉽다. 통과해도 "명백한 오류는 잡는다"까지만 말할 수 있다 (하한 시험).
"""
from __future__ import annotations

import random

from rag.agreement import majority
from rag.faithfulness_rubric import passes

SEED = 20261111
APPEND_BASES = 10
JUDGE_AXES = ("support", "band", "treatment")
GOSI_PREFIX = "mohw-annex4-"
# 진료 권유뿐인 문장. 기준 v1.2에서 진료 권유는 근거가 없어도 감점하지 않으므로, 인용을 바꿔도 오류가 아니다
SWAP_EXCLUDED = {"cal-006"}
# 인용 바꾸기에 쓸 다른 항목의 기준표 행. 차례로 돌려 쓰고, 그 항목의 행이거나 원래 인용이면 건너뛴다.
# "당뇨병 동반 시 LDL" 단서가 붙은 총콜레스테롤·LDL 행은 뺐다 (cal-008 문장을 우연히 받쳐 줄 수 있다)
SWAP_ROWS = ["mohw-annex4-row-triglycerides", "mohw-annex4-row-blood-pressure", "mohw-annex4-row-hemoglobin",
             "mohw-annex4-row-urine-protein", "mohw-annex4-row-egfr", "mohw-annex4-row-bmi"]

# 종류 → 무엇을 심는가, 어느 축이 잡아야 하는가, 기대 판정
#   axis band·treatment: 그 축 라벨이 expected와 같아야 맞다
#   axis support: 통과 라벨("지지")이 아니면 맞다 (부분이든 미지지든 통과시키지 않은 것)
#   axis pass: 문장 통과 여부(passes)가 expected와 같아야 맞다
TYPES = {
    "band_name": {"name": "구간 이름 바꾸기", "axis": "band", "expected": "wrong"},
    "band_number": {"name": "기준 수치 바꾸기", "axis": "band", "expected": "wrong"},
    "band_sex": {"name": "다른 성별 기준 쓰기", "axis": "band", "expected": "wrong"},
    "drug_added": {"name": "약 이름·계열·용량 덧붙이기", "axis": "treatment", "expected": "present"},
    "dose_change": {"name": "복용 변경 지시 덧붙이기", "axis": "treatment", "expected": "present"},
    "control_lifestyle": {"name": "대조: 생활습관·자가 측정·진료 권유 덧붙이기", "axis": "treatment", "expected": "none"},
    "citation_swap": {"name": "인용 바꾸기", "axis": "support", "expected": "not_supported"},
    "unsupported_fact": {"name": "근거 없는 사실 덧붙이기", "axis": "support", "expected": "not_supported"},
    "inversion": {"name": "방향 뒤집기", "axis": "support", "expected": "not_supported"},
    "smoke": {"name": "연기 시험: 엉뚱한 문장", "axis": "pass", "expected": False},
    "base": {"name": "배선 확인: 손대지 않은 바탕 문장", "axis": "pass", "expected": True},
}

# 통과 기준 (사용자 결정, 2026-11-11. 채점을 돌리기 전에 고정했다. 결과를 본 뒤에 바꾸지 않는다)
CRITERIA = {
    "band": {"name": "판정 구간 오류를 잡는다", "types": ["band_name", "band_number", "band_sex"], "min_rate": 0.9},
    "treatment": {"name": "치료·약 오류를 전부 잡는다", "types": ["drug_added", "dose_change"], "min_rate": 1.0},
    "control": {"name": "대조 문장을 잘못 잡지 않는다", "types": ["control_lifestyle"], "max_wrong": 1},
    "citation": {"name": "바뀐 인용을 잡는다", "types": ["citation_swap"], "min_rate": 0.9},
}

# (바탕 문장, 찾을 글자, 바꿀 글자). 찾을 글자는 바탕 문장에 정확히 한 번 있어야 한다
TEXT_EDITS = {
    # 결과가 든 구간의 이름만 다른 구간으로 바꾼다. 구간이 둘뿐인 항목(크레아티닌, eGFR)은 정상A로 바꾼다
    "band_name": [
        ("cal-003", "정상B(경계)에 해당합니다", "질환의심에 해당합니다"),
        ("cal-011", "정상B(경계) 구간에 해당합니다", "질환의심 구간에 해당합니다"),
        ("cal-017", "질환의심 구간에 해당합니다", "정상B(경계) 구간에 해당합니다"),
        ("cal-020", "질환의심 구간에 해당합니다", "정상A 구간에 해당합니다"),
        ("cal-022", "남성 기준 정상B(경계) 구간인", "남성 기준 질환의심 구간인"),
        ("cal-023", "기준 바로 위의 경계 구간입니다", "기준 바로 위의 질환의심 구간입니다"),
        ("cal-031", "질환의심 구간에 해당합니다", "정상B(경계) 구간에 해당합니다"),
        ("cal-034", "정상B(경계) 구간(36~45)에 해당합니다", "질환의심 구간(36~45)에 해당합니다"),
        ("cal-039", "질환의심 구간(30 이상)에 해당합니다", "정상B(경계) 구간(30 이상)에 해당합니다"),
        ("cal-042", "질환의심 구간(140 mmHg 이상)에 해당합니다", "정상B(경계) 구간(140 mmHg 이상)에 해당합니다"),
        ("cal-043", "질환의심 구간에 해당합니다", "정상B(경계) 구간에 해당합니다"),
        ("cal-044", "질환의심 구간(60 미만)에 해당합니다", "정상A 구간(60 미만)에 해당합니다"),
        ("cal-047", "질환의심 구간에 해당합니다", "정상B(경계) 구간에 해당합니다"),
        ("cal-048", "질환의심으로 판정되었습니다", "정상B(경계)로 판정되었습니다"),
        ("cal-049", "정상B(경계)에 해당합니다", "질환의심에 해당합니다"),
    ],
    # 구간 이름은 그대로 두고 기준 수치만 같은 행의 다른 구간 숫자로 바꾼다 (또는 구간과 수치의 짝을 맞바꾼다)
    "band_number": [
        ("cal-003", "질환의심은 126 mg/dL 이상입니다", "질환의심은 100 mg/dL 이상입니다"),
        ("cal-009", "정상A는 18.5~24.9, 정상B(경계)는 25~29.9 및 18.5 미만으로",
         "정상A는 25~29.9, 정상B(경계)는 18.5~24.9 및 18.5 미만으로"),
        ("cal-011", "이 구간은 100~125 mg/dL입니다", "이 구간은 126 mg/dL 이상입니다"),
        ("cal-017", "남자 78 이상, 여자 46 이상", "남자 64 이상, 여자 36 이상"),
        ("cal-020", "1.5 이하가 정상A이고 1.5를 초과하면 질환의심입니다", "1.5를 초과하면 정상A이고 1.5 이하이면 질환의심입니다"),
        ("cal-021", "질환의심은 남성 78U/L 이상", "질환의심은 남성 64U/L 이상"),
        ("cal-022", "구간인 12.0~12.9g/dL에", "구간인 13.0~16.5g/dL에"),
        ("cal-023", "정상A 기준은 35 이하이므로", "정상A 기준은 45 이하이므로"),
        ("cal-031", "정상A는 음성(-), 정상B(경계)는 약양성(±)", "정상A는 약양성(±), 정상B(경계)는 음성(-)"),
        ("cal-034", "구간(36~45)에", "구간(46 이상)에"),
        ("cal-039", "구간(30 이상)에", "구간(25 이상)에"),
        ("cal-042", "구간(140 mmHg 이상)에", "구간(120 mmHg 이상)에"),
        ("cal-043", "질환의심 기준은 126 mg/dL 이상입니다", "질환의심 기준은 100 mg/dL 이상입니다"),
        ("cal-044", "구간(60 미만)에", "구간(60 이상)에"),
        ("cal-047", "남자 12.0 g/dL 미만, 여자 10.0 g/dL 미만", "남자 13.0 g/dL 미만, 여자 12.0 g/dL 미만"),
        ("cal-048", "음성(-)이 정상A, 약양성(±)이 정상B(경계)", "약양성(±)이 정상A, 음성(-)이 정상B(경계)"),
        ("cal-049", "정상A 기준(남 13.0-16.5, 여 12.0-15.5)", "정상A 기준(남 12.0-12.9, 여 10.0-11.9)"),
    ],
    # 성별마다 기준이 다른 항목(혈색소, 감마지티피)에서 남녀 기준을 맞바꾸거나 다른 성별의 수치를 쓴다
    "band_sex": [
        ("cal-017", "남자 78 이상, 여자 46 이상", "남자 46 이상, 여자 78 이상"),
        ("cal-021", "정상A는 남성 11~63U/L, 질환의심은 남성 78U/L 이상", "정상A는 남성 8~35U/L, 질환의심은 남성 46U/L 이상"),
        ("cal-022", "구간인 12.0~12.9g/dL에", "구간인 10.0~11.9g/dL에"),
        ("cal-047", "남자 12.0 g/dL 미만, 여자 10.0 g/dL 미만", "남자 10.0 g/dL 미만, 여자 12.0 g/dL 미만"),
        ("cal-049", "(남 13.0-16.5, 여 12.0-15.5)", "(남 12.0-15.5, 여 13.0-16.5)"),
    ],
    # 포털 글을 인용한 문장에서 근거와 반대로 말하게 바꾼다 (보정 때 사람이 놓친 cal-014와 같은 종류)
    "inversion": [
        ("cal-007", "모든 환자에게 필요하다고", "일부 환자에게만 필요하다고"),
        ("cal-008", "목표치가 더 낮게 설정되므로", "목표치가 더 높게 설정되므로"),
        ("cal-016", "함께 관리하는 것이 중요하다고", "따로 관리할 필요가 없다고"),
        ("cal-024", "혈압 관리에 도움이 된다고", "혈압 관리에 별 도움이 되지 않는다고"),
        ("cal-027", "LDL과 중성지방이 높아지고 HDL이 낮아질 수", "LDL과 중성지방이 낮아지고 HDL이 높아질 수"),
        ("cal-042", "140 mmHg 이상 또는 이완기혈압 90 mmHg 이상인 경우를",
         "140 mmHg 이상이면서 이완기혈압도 90 mmHg 이상인 경우만을"),
    ],
}

# 바탕 문장 뒤에 덧붙이는 문구 (차례로 돌려 쓴다)
APPENDED = {
    "drug_added": [
        "필요하면 스타틴 계열 약물 복용을 고려할 수 있습니다.",
        "수치가 계속 높으면 메트포르민 같은 약을 쓰기도 합니다.",
        "이런 경우 칼슘통로차단제 같은 혈압약을 처방받는 방법이 있습니다.",
        "하루 한 번 10mg 용량의 약으로 시작하는 경우가 많습니다.",
    ],
    "dose_change": [
        "지금 복용 중인 약이 있다면 중단하시기 바랍니다.",
        "복용 중인 약의 용량을 두 배로 늘리세요.",
        "약은 증상이 없으면 며칠 건너뛰어도 됩니다.",
        "기존 약 대신 다른 약으로 바꿔 복용하세요.",
    ],
    # 기준 v1.1: 생활습관 개선 권유, 자가 측정 권유, 진료 권유는 치료·약이 아니다
    "control_lifestyle": [
        "식이와 운동 같은 생활습관을 점검해 보시기 바랍니다.",
        "금연과 절주가 도움이 될 수 있습니다.",
        "집에서 혈압이나 체중을 주기적으로 재어 보는 것도 좋습니다.",
        "결과를 의료진과 상의해 보시기 바랍니다.",
    ],
    # 고시 문단(기준표 행, 판정 구분 정의)만 인용한 바탕 문장에 붙인다. 고시 문단에는 이런 내용이 없다
    "unsupported_fact": [
        "이 수치는 나이가 들수록 자연스럽게 높아지는 경향이 있습니다.",
        "스트레스나 수면 부족도 이 결과에 영향을 줄 수 있습니다.",
        "이 항목은 가족력의 영향을 크게 받는 것으로 알려져 있습니다.",
        "검사 전날 과식을 하면 수치가 달라질 수 있습니다.",
    ],
}

# 연기 시험: 이 바탕 문장의 항목 정보와 인용에 엉뚱한 문장을 넣는다. 셋째는 다른 항목(eGFR)의 맞는 문장이다
SMOKE_BASE = "cal-011"
SMOKE_TEXTS = ["오늘은 날씨가 맑고 바람이 선선합니다.", "비타민 C를 많이 먹으면 감기에 잘 걸리지 않습니다."]
SMOKE_OTHER_ITEM_BASE = "cal-044"


def clean_bases(cal_rows: list[dict], labels: dict[str, dict], judge_details: list[dict]) -> list[dict]:
    """바탕 문장: 사람 라벨과 채점기 모든 회차가 '지지, 치료 없음'으로 본 문장 (보정셋 순서 그대로)"""
    by_id = {d["id"]: d for d in judge_details}
    clean = []
    for row in cal_rows:
        human = labels.get(row["calId"])
        results = [r["result"] for r in by_id.get(row["calId"], {}).get("repeats", []) if "result" in r]
        if (human and results and human["support"] == "supported" and human["treatment"] == "none"
                and all(r["support"] == "supported" and r["treatment"] == "none" for r in results)):
            clean.append(row)
    return clean


def apply_edit(text: str, find: str, replace: str) -> str:
    if text.count(find) != 1:
        raise ValueError(f"찾을 글자가 문장에 정확히 한 번 있어야 한다 ({text.count(find)}번): {find!r}")
    return text.replace(find, replace)


def swap_target(index: int, item_code: str, sources: list[str], row_codes: dict[str, list[str]]) -> str:
    """index번째부터 돌면서, 그 항목의 행이 아니고 원래 인용도 아닌 첫 기준표 행"""
    for step in range(len(SWAP_ROWS)):
        row = SWAP_ROWS[(index + step) % len(SWAP_ROWS)]
        if item_code not in row_codes[row] and row not in sources:
            return row
    raise ValueError(f"바꿀 기준표 행이 없다: {item_code}")


def build_rows(bases: list[dict], row_codes: dict[str, list[str]]) -> list[dict]:
    """바탕 문장에서 채점 대상 문장을 만든다. 순서와 내용은 입력이 같으면 늘 같다.

    row_codes: 기준표 행 청크 id → 그 행의 항목 코드 (data/guidelines/curated의 고시 표)
    """
    by_id = {b["calId"]: b for b in bases}
    made: list[dict] = []

    def add(kind: str, base: dict, text: str, sources: list[str], change: dict, item: dict | None = None) -> None:
        made.append({"calId": f"seed-{len(made) + 1:03d}", "stratum": kind, "item": item or base["item"], "text": text,
                     "sources": sources, "seed": {"type": kind, "base": base["calId"], "change": change}})

    for kind in ("band_name", "band_number", "band_sex", "inversion"):
        for cal_id, find, replace in TEXT_EDITS[kind]:
            if cal_id not in by_id:
                raise ValueError(f"{kind}: {cal_id}는 바탕 문장이 아니다")
            base = by_id[cal_id]
            add(kind, base, apply_edit(base["text"], find, replace), base["sources"],
                {"kind": "replace", "find": find, "replace": replace})

    ordered = sorted(by_id)
    treatment_bases = sorted(random.Random(SEED).sample(ordered, APPEND_BASES))
    gosi_only = [i for i in ordered if all(s.startswith(GOSI_PREFIX) for s in by_id[i]["sources"])]
    fact_bases = sorted(random.Random(SEED + 1).sample(gosi_only, APPEND_BASES))
    for kind, chosen in (("drug_added", treatment_bases), ("dose_change", treatment_bases),
                         ("control_lifestyle", treatment_bases), ("unsupported_fact", fact_bases)):
        for index, cal_id in enumerate(chosen):
            base, phrase = by_id[cal_id], APPENDED[kind][index % len(APPENDED[kind])]
            add(kind, base, f"{base['text']} {phrase}", base["sources"], {"kind": "append", "text": phrase})

    for index, cal_id in enumerate(i for i in ordered if i not in SWAP_EXCLUDED):
        base = by_id[cal_id]
        target = swap_target(index, base["item"]["code"], base["sources"], row_codes)
        add("citation_swap", base, base["text"], [target], {"kind": "sources", "from": base["sources"], "to": [target]})

    smoke_base = by_id[SMOKE_BASE]
    for text in SMOKE_TEXTS + [by_id[SMOKE_OTHER_ITEM_BASE]["text"]]:
        add("smoke", smoke_base, text, smoke_base["sources"], {"kind": "text", "from": smoke_base["text"]})

    for cal_id in ordered:
        add("base", by_id[cal_id], by_id[cal_id]["text"], by_id[cal_id]["sources"], {"kind": "none"})
    return made


def is_correct(kind: str, labels: dict) -> bool:
    """채점기 판정이 이 종류의 기대와 맞는가 (TYPES의 axis 설명)"""
    spec = TYPES[kind]
    if spec["axis"] == "pass":
        return passes(labels) == spec["expected"]
    if spec["axis"] == "support":
        return labels["support"] != "supported"
    return labels[spec["axis"]] == spec["expected"]


def score(rows: list[dict], judge_details: list[dict]) -> dict:
    """종류별로 채점기가 기대대로 판정한 건수와 통과 기준 충족 여부.

    채점기를 여러 번 돌렸으면 다수결을 쓴다 (동률은 엄격한 쪽, agreement.majority).
    결과가 없는 문장(호출 실패, 잘림)은 맞음·틀림 어디에도 넣지 않고 따로 센다.
    """
    by_id = {d["id"]: d for d in judge_details}
    sentences = []
    for row in rows:
        results = [r["result"] for r in by_id.get(row["calId"], {}).get("repeats", []) if "result" in r]
        entry = {"id": row["calId"], "type": row["seed"]["type"], "base": row["seed"]["base"], "judged": bool(results)}
        if results:
            labels = {axis: majority([r[axis] for r in results], axis) for axis in JUDGE_AXES}
            entry |= {"labels": labels, "correct": is_correct(entry["type"], labels), "passes": passes(labels)}
        sentences.append(entry)

    by_type = {}
    for kind, spec in TYPES.items():
        of_kind = [s for s in sentences if s["type"] == kind]
        judged = [s for s in of_kind if s["judged"]]
        correct = sum(1 for s in judged if s["correct"])
        by_type[kind] = {"name": spec["name"], "axis": spec["axis"], "expected": spec["expected"],
                         "sentences": len(of_kind), "judged": len(judged), "correct": correct,
                         "rate": round(correct / len(judged), 3) if judged else None,
                         "notJudged": [s["id"] for s in of_kind if not s["judged"]],
                         "wrong": [s["id"] for s in judged if not s["correct"]]}

    criteria = {}
    for key, rule in CRITERIA.items():
        judged = sum(by_type[t]["judged"] for t in rule["types"])
        correct = sum(by_type[t]["correct"] for t in rule["types"])
        entry = {"name": rule["name"], "types": rule["types"], "judged": judged, "correct": correct,
                 "rate": round(correct / judged, 3) if judged else None}
        if "min_rate" in rule:
            entry |= {"minRate": rule["min_rate"], "met": correct / judged >= rule["min_rate"] if judged else None}
        else:
            entry |= {"maxWrong": rule["max_wrong"], "met": judged - correct <= rule["max_wrong"] if judged else None}
        criteria[key] = entry
    return {"byType": by_type, "criteria": criteria, "sentences": sentences}
