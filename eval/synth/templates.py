"""레이아웃 템플릿 (개발용 3종 + 일반화 시험용 3종). 각 템플릿은 (문서 블록, 정답 JSON, 메타)를 돌려준다.

정답 JSON의 문자열 값은 문서에 "찍힌 그대로"다. 그래서 정답은 렌더링과 항상 일치한다.
"""
from __future__ import annotations

import random
from dataclasses import dataclass

from .sampling import Measured, Subject, _previous

SYNTHETIC_NOTICE = "※ 이 문서는 테스트용 합성 데이터입니다. 실제 개인 정보나 검진 결과가 아닙니다."
FAKE_CENTER = "가상건강검진센터"


# ---- 문서 블록 (텍스트·PDF 렌더러가 공통으로 쓴다) ----

@dataclass
class Heading:
    text: str


@dataclass
class Para:
    text: str


@dataclass
class Table:
    header: list[str]
    rows: list[list[str]]


Block = Heading | Para | Table


def _expected_item(m: Measured, raw_name: str, *, value: str | None = None, unit: str | None = None,
                   reference: str | None = None, judgment: str | None = None) -> dict:
    return {
        "code": m.code,
        "rawName": raw_name,
        "value": value if value is not None else m.value,
        "unit": unit,
        "referenceRaw": reference,
        "centerJudgment": judgment,
    }


def _expected(subject: Subject, exam_type: str, items: list[dict], *, age_printed: bool) -> dict:
    return {
        "examDate": subject.exam_date.isoformat(),
        "examType": exam_type,
        "sex": subject.sex,
        "age": subject.age if age_printed else None,
        "items": items,
    }


def _by_code(items: list[Measured]) -> dict[str, Measured]:
    return {m.code: m for m in items if m.code != "OTHER"}


# ---- 템플릿 1: 공단 표준 서식형 ----

GROUPS = {
    "HEIGHT": "계측검사", "WEIGHT": "계측검사", "WAIST_CIRCUMFERENCE": "계측검사", "BMI": "계측검사",
    "SYSTOLIC_BP": "계측검사", "VISION_LEFT": "계측검사", "VISION_RIGHT": "계측검사",
    "HEARING_LEFT": "계측검사", "HEARING_RIGHT": "계측검사", "URINE_PROTEIN": "요검사",
    "CHEST_XRAY": "영상검사",
}


def standard(rng: random.Random, subject: Subject, items: list[Measured], labels: dict) -> tuple:
    rows, expected = [], []
    codes = _by_code(items)
    for m in items:
        if m.code == "DIASTOLIC_BP":
            continue  # 수축기 행에서 함께 찍는다
        group = GROUPS.get(m.code, "혈액검사")
        judgment = labels[m.band] if m.band else None
        if m.code == "SYSTOLIC_BP":
            dia = codes["DIASTOLIC_BP"]
            name, reference = "혈압(수축기/이완기)", "120/80 미만"
            rows.append([group, name, f"{m.value}/{dia.value}", "mmHg", reference, judgment or "-"])
            expected.append(_expected_item(m, name, unit="mmHg", reference=reference, judgment=judgment))
            expected.append(_expected_item(dia, name, unit="mmHg", reference=reference, judgment=judgment))
            continue
        name = m.names[0]
        rows.append([group, name, m.value, m.unit or "-", m.reference or "-", judgment or "-"])
        expected.append(_expected_item(m, name, unit=m.unit, reference=m.reference, judgment=judgment))

    flagged = [m.names[0] for m in items if m.band in ("borderline", "abnormal")]
    sex_ko = "남" if subject.sex == "MALE" else "여"
    blocks: list[Block] = [
        Heading("일반건강검진 결과통보서"),
        Para(SYNTHETIC_NOTICE),
        Heading("수검자 정보"),
        Table(["성명", "생년월일", "성별", "나이", "검진일", "검진기관"],
              [[subject.masked_name, f"{subject.birth_year}-**-**", sex_ko, f"만 {subject.age}세",
                subject.exam_date.isoformat(), FAKE_CENTER]]),
        Heading("검사 결과"),
        Table(["구분", "검사항목", "결과", "단위", "참고치", "판정"], rows),
        Heading("종합 소견"),
        Para("추적 관찰이 필요한 항목: " + (", ".join(flagged) if flagged else "없음")
             + ". 자세한 내용은 담당 의사와 상담하시기 바랍니다."),
    ]
    meta = {"distractors": [], "age_printed": True}
    return blocks, _expected(subject, "GENERAL", expected, age_printed=True), meta


# ---- 템플릿 2: 기관 요약표형 (종합검진, 영문 약어, 전회 결과 칸) ----

def _short_reference(reference: str | None) -> str:
    if not reference:
        return ""
    for word, symbol in (("미만", "<"), ("이하", "≤"), ("이상", "≥")):
        if reference.endswith(word):
            return symbol + reference.removesuffix(word).strip()
    return reference.replace("~", "-")


def clinic_summary(rng: random.Random, subject: Subject, items: list[Measured], labels: dict) -> tuple:
    rows, expected = [], []
    for m in items:
        name = m.names[1]
        reference = _short_reference(m.reference) or None
        rows.append([name, m.value, m.previous, m.unit or "", reference or ""])
        expected.append(_expected_item(m, name, unit=m.unit, reference=reference))

    sex_code = "M" if subject.sex == "MALE" else "F"
    blocks: list[Block] = [
        Heading("가상의원 종합건강검진 결과 요약"),
        Para(SYNTHETIC_NOTICE),
        Para(f"수검번호: SYN-2026-{rng.randint(1000, 9999)}    이름: {subject.masked_name} "
             f"({sex_code}/{subject.age})    검사일: {subject.exam_date.strftime('%Y.%m.%d')}"),
        Table(["검사명", "금회", "전회", "단위", "참고치"], rows),
        Para("※ 전회 결과는 직전 검진 기록입니다. 이상 소견은 담당 의사와 상담하시기 바랍니다."),
    ]
    meta = {"distractors": ["previous_column"], "age_printed": True}
    return blocks, _expected(subject, "COMPREHENSIVE", expected, age_printed=True), meta


# ---- 템플릿 3: 목록 + 의사 소견형 (나이 없음, 소견 속 전회 수치) ----

def _short_label(label: str) -> str:
    return label.split("(")[0]


def narrative(rng: random.Random, subject: Subject, items: list[Measured], labels: dict) -> tuple:
    codes = _by_code(items)
    expected: list[dict] = []

    def judged(m: Measured) -> str | None:
        return _short_label(labels[m.band]) if m.band else None

    def inline(m: Measured, *, space: bool) -> str:
        """'172.4cm (정상A)' 형태로 찍고 정답을 기록한다."""
        judgment = judged(m)
        expected.append(_expected_item(m, m.names[2], unit=m.unit, judgment=judgment))
        unit = (" " if space else "") + m.unit if m.unit else ""
        return f"{m.names[2]} {m.value}{unit}" + (f" ({judgment})" if judgment else "")

    lines = ["■ 신체 계측"]
    lines.append("· " + ", ".join(inline(codes[c], space=False)
                                  for c in ("HEIGHT", "WEIGHT", "BMI", "WAIST_CIRCUMFERENCE")))
    sbp, dbp = codes["SYSTOLIC_BP"], codes["DIASTOLIC_BP"]
    bp_judgment = judged(sbp)
    expected.append(_expected_item(sbp, "혈압 수축기", unit="mmHg", judgment=bp_judgment))
    expected.append(_expected_item(dbp, "이완기", unit="mmHg", judgment=bp_judgment))
    lines.append(f"· 혈압 수축기 {sbp.value}mmHg / 이완기 {dbp.value}mmHg ({bp_judgment})")
    if "VISION_LEFT" in codes:
        for c in ("VISION_LEFT", "VISION_RIGHT", "HEARING_LEFT", "HEARING_RIGHT"):
            expected.append(_expected_item(codes[c], codes[c].names[2]))
        lines.append("· " + ", ".join(f"{codes[c].names[2]} {codes[c].value}"
                                      for c in ("VISION_LEFT", "VISION_RIGHT", "HEARING_LEFT", "HEARING_RIGHT")))

    lines.append("■ 소변 검사")
    lines.append("· " + inline(codes["URINE_PROTEIN"], space=True))

    lines.append("■ 혈액 검사")
    blood = [m for m in items if m.code not in GROUPS and m.code not in ("DIASTOLIC_BP", "OTHER")]
    for m in blood:
        judgment = judged(m)
        expected.append(_expected_item(m, m.names[2], unit=m.unit, reference=m.reference, judgment=judgment))
        reference = f" (참고 {m.reference})" if m.reference else ""
        lines.append(f"· {m.names[2]} {m.value} {m.unit}{reference}" + (f" → {judgment}" if judgment else ""))

    lines.append("■ 흉부 X선")
    lines.append("· " + inline(codes["CHEST_XRAY"], space=True))

    # 의사 소견: 경계/이상 항목 하나를 골라 '전회 수치'를 문장에 넣는다 (방해 요소)
    numeric_flagged = [m for m in blood if m.band in ("borderline", "abnormal")]
    target = rng.choice(numeric_flagged) if numeric_flagged else rng.choice(blood)
    comment = (f"{target.names[2]} 수치는 지난 검진 때 {target.previous} {target.unit}에서 이번에 "
               f"{target.value} {target.unit}로 변했습니다. 생활습관을 점검하시고 정기적으로 추적 검사를 받으세요. "
               "이 안내문은 진단서가 아니며, 정확한 판단은 담당 의사와 상담하시기 바랍니다.")

    sex_ko = "남성" if subject.sex == "MALE" else "여성"
    blocks: list[Block] = [
        Heading("[가상내과의원] 건강검진 결과 안내"),
        Para(SYNTHETIC_NOTICE),
        Para(f"{subject.masked_name} 님 ({sex_ko}, {subject.birth_year}-**-** 생)"),
        Para(f"검진일자: {subject.exam_date.year}년 {subject.exam_date.month}월 {subject.exam_date.day}일"),
        *[Heading(line[2:]) if line.startswith("■") else Para(line) for line in lines],
        Heading("의사 소견"),
        Para(comment),
    ]
    meta = {"distractors": ["comment_previous_value"], "age_printed": False}
    return blocks, _expected(subject, "GENERAL", expected, age_printed=False), meta


# ---- 템플릿 4 (일반화 시험용, held-out): 검사실 결과지형 ----
#
# 검증 규칙을 만들 때 보지 않은 양식이다. 규칙에 맞추거나 깨려고 고르지 않고, 실제 결과지에서 흔하지만
# 템플릿 1~3에 없던 특징만 넣었다 (2026-11-09 설계).
#   - 신체계측을 2단 표(항목|결과|항목|결과)로 찍는다: 한 줄에 두 항목인데 쉼표가 없다
#   - 시력·청력은 좌우를 한 칸에 찍는다 ("1.0/0.8", "정상/정상")
#   - 열 순서: 결과 | 판정 | 참고범위 | 단위. 판정 어휘는 정상/경계/이상 (국가검진 등급이 아니다)
#   - 항목명은 "공복혈당(FBS)"처럼 이름(약어)
#   - 등록번호, 채혈일(2026/10/14), 문의 전화번호처럼 결과가 아닌 숫자가 있다

LAB_LABELS = {"normal": "정상", "borderline": "경계", "abnormal": "이상"}
SUMMARY_CODES = {"HEIGHT", "WEIGHT", "WAIST_CIRCUMFERENCE", "BMI", "SYSTOLIC_BP", "DIASTOLIC_BP"} | {
    "VISION_LEFT", "VISION_RIGHT", "HEARING_LEFT", "HEARING_RIGHT"}


def _lab_name(m: Measured) -> str:
    standard_name, abbr = m.names[0], m.names[1]
    return abbr if standard_name in abbr else f"{standard_name}({abbr})"


def lab_report(rng: random.Random, subject: Subject, items: list[Measured], labels: dict) -> tuple:
    codes = _by_code(items)
    expected: list[dict] = []

    # 2단 요약표: 판정·참고치 없이 "값 단위"만 찍는다
    cells: list[tuple[str, str]] = []
    for code in ("HEIGHT", "WEIGHT", "WAIST_CIRCUMFERENCE", "BMI"):
        m = codes[code]
        cells.append((m.names[0], f"{m.value} {m.unit}"))
        expected.append(_expected_item(m, m.names[0], unit=m.unit))
    sbp, dbp = codes["SYSTOLIC_BP"], codes["DIASTOLIC_BP"]
    cells.append(("혈압(수축기/이완기)", f"{sbp.value}/{dbp.value} mmHg"))
    expected.append(_expected_item(sbp, "혈압(수축기/이완기)", unit="mmHg"))
    expected.append(_expected_item(dbp, "혈압(수축기/이완기)", unit="mmHg"))
    if "VISION_LEFT" in codes:
        for name, left, right in (("시력(좌/우)", "VISION_LEFT", "VISION_RIGHT"),
                                  ("청력(좌/우)", "HEARING_LEFT", "HEARING_RIGHT")):
            cells.append((name, f"{codes[left].value}/{codes[right].value}"))
            expected.append(_expected_item(codes[left], name))
            expected.append(_expected_item(codes[right], name))
    if len(cells) % 2:
        cells.append(("", ""))
    summary_rows = [[*cells[i], *cells[i + 1]] for i in range(0, len(cells), 2)]

    # 상세표: 혈액·소변 검사와 추가 검사
    rows = []
    for m in items:
        if m.code in SUMMARY_CODES or m.code == "CHEST_XRAY":
            continue
        name = _lab_name(m)
        judgment = LAB_LABELS[m.band] if m.band else None
        rows.append([name, m.value, judgment or "", m.reference or "", m.unit or ""])
        expected.append(_expected_item(m, name, unit=m.unit, reference=m.reference, judgment=judgment))

    xray = codes["CHEST_XRAY"]
    xray_judgment = LAB_LABELS[xray.band] if xray.band else None
    expected.append(_expected_item(xray, "흉부 X-ray", judgment=xray_judgment))

    sex_ko = "남" if subject.sex == "MALE" else "여"
    blocks: list[Block] = [
        Heading("가상병원 진단검사의학과 검사 결과 보고서"),
        Para(SYNTHETIC_NOTICE),
        Para(f"등록번호: SYN-{rng.randint(10000, 99999)}    성별/나이: {sex_ko}/{subject.age}    "
             f"채혈일: {subject.exam_date.strftime('%Y/%m/%d')}"),
        Heading("신체 계측 및 기본 검사"),
        Table(["항목", "결과", "항목", "결과"], summary_rows),
        Heading("혈액·소변 검사"),
        Table(["검사항목", "결과", "판정", "참고범위", "단위"], rows),
        Heading("영상 검사"),
        Table(["검사", "판독 소견", "판정"], [["흉부 X-ray", xray.value, xray_judgment or ""]]),
        Para("※ 참고범위는 검사 방법에 따라 다를 수 있습니다. 판정: 정상 / 경계 / 이상"),
        Para("문의: 진단검사의학과 000-0000-0000"),
    ]
    meta = {"distractors": ["two_column_table", "pair_in_one_cell", "judgment_after_result", "lab_vocabulary",
                            "non_result_numbers"], "age_printed": True}
    return blocks, _expected(subject, "UNKNOWN", expected, age_printed=True), meta


TEMPLATES = {
    "std": standard,        # 공단 표준 서식형
    "sum": clinic_summary,  # 기관 요약표형
    "nar": narrative,       # 목록 + 의사 소견형
}

# ---- 템플릿 5 (일반화 시험용, held-out v2): 3개년 추이표형 ----
#
# 검증 v2.3을 만들기 전에 설계·생성했다 (2026-11-09). heldout-v1(lab)의 결과를 본 뒤라 lab은 더 이상
# 처음 보는 양식이 아니기 때문이다. 실제 결과지에서 흔하지만 템플릿 1~4에 없던 특징만 넣었다.
#   - 한 행에 2024, 2025, 2026(이번) 세 해의 값이 있다. 이번 값은 세 번째 열이다
#   - 열 순서: 검사항목 | 단위 | 연도들 | 참고치 | 판정 (단위가 값 앞)
#   - 시력·청력은 "시력: 좌 1.0 우 0.8 / 청력: 좌 정상 우 정상" 한 줄
#   - 판정 어휘 정상/주의/재검
#   - 고객번호, 대표번호(1588-0000), 상담 시간처럼 결과가 아닌 숫자

TREND_LABELS = {"normal": "정상", "borderline": "주의", "abnormal": "재검"}


def _older(rng: random.Random, m: Measured) -> str:
    """2년 전 값. 숫자는 작년 값의 근사치, 글자 값은 이번·작년 값 중 하나."""
    try:
        number = float(m.previous)
    except ValueError:
        return rng.choice([m.previous, m.value])
    decimals = len(m.value.split(".")[1]) if "." in m.value else 0
    return _previous(rng, number, decimals)


def trend_report(rng: random.Random, subject: Subject, items: list[Measured], labels: dict) -> tuple:
    codes = _by_code(items)
    expected: list[dict] = []
    rows = []
    for m in items:
        if m.code in ("DIASTOLIC_BP", "VISION_LEFT", "VISION_RIGHT", "HEARING_LEFT", "HEARING_RIGHT", "CHEST_XRAY"):
            continue
        judgment = TREND_LABELS[m.band] if m.band else None
        if m.code == "SYSTOLIC_BP":
            dbp = codes["DIASTOLIC_BP"]
            name = "혈압(수축기/이완기)"
            rows.append([name, "mmHg", f"{_older(rng, m)}/{_older(rng, dbp)}", f"{m.previous}/{dbp.previous}",
                         f"{m.value}/{dbp.value}", "120/80 미만", judgment or ""])
            expected.append(_expected_item(m, name, unit="mmHg", reference="120/80 미만", judgment=judgment))
            expected.append(_expected_item(dbp, name, unit="mmHg", reference="120/80 미만", judgment=judgment))
            continue
        name = m.names[0]
        older = "-" if m.code == "OTHER" and rng.random() < 0.5 else _older(rng, m)  # 추가 검사는 2년 전 기록이 없기도 하다
        rows.append([name, m.unit or "", older, m.previous, m.value, m.reference or "", judgment or ""])
        expected.append(_expected_item(m, name, unit=m.unit, reference=m.reference, judgment=judgment))

    senses = ""
    if "VISION_LEFT" in codes:
        vl, vr, hl, hr = (codes[c] for c in ("VISION_LEFT", "VISION_RIGHT", "HEARING_LEFT", "HEARING_RIGHT"))
        senses = f"시력: 좌 {vl.value} 우 {vr.value} / 청력: 좌 {hl.value} 우 {hr.value}"
        for m, name in ((vl, "시력"), (vr, "시력"), (hl, "청력"), (hr, "청력")):
            expected.append(_expected_item(m, name))

    xray = codes["CHEST_XRAY"]
    xray_judgment = TREND_LABELS[xray.band] if xray.band else None
    expected.append(_expected_item(xray, "흉부 X선", judgment=xray_judgment))

    sex_ko = "남" if subject.sex == "MALE" else "여"
    d = subject.exam_date
    blocks: list[Block] = [
        Heading("건강검진 3개년 추이 보고서"),
        Para(SYNTHETIC_NOTICE),
        Para(f"고객번호 C-{rng.randint(100000, 999999)} / 성별 {sex_ko} / 나이 {subject.age}세 / "
             f"검진일 {d.year}. {d.month}. {d.day}."),
        Heading("검사 결과 추이 (2024~2026)"),
        Table(["검사항목", "단위", "2024", "2025", "2026(이번)", "참고치", "판정"], rows),
        *([Heading("시력·청력"), Para(senses)] if senses else []),
        Heading("영상 검사"),
        Para(f"흉부 X선 소견: {xray.value}" + (f" (판정: {xray_judgment})" if xray_judgment else "")),
        Para("※ 2024·2025년 값은 비교용입니다. 판정은 2026년 결과 기준입니다."),
        Para("상담 문의 1588-0000 (평일 09:00~18:00)"),
    ]
    meta = {"distractors": ["three_year_columns", "unit_before_values", "senses_slash_line", "trend_vocabulary",
                            "non_result_numbers"], "age_printed": True}
    return blocks, _expected(subject, "UNKNOWN", expected, age_printed=True), meta


# ---- 템플릿 6 (일반화 시험용, held-out v3): 안내문형 ----
#
# 검증 v2.4(항목 모양 규칙)를 정한 뒤, 그 규칙으로 이 양식을 돌려 보기 전에 설계·생성했다 (2026-11-09).
# heldout-v2(trend)는 v2.4 규칙을 만들 때 실패 사례를 봤으므로 더 이상 처음 보는 양식이 아니다.
# 실제 결과지에서 흔하지만 템플릿 1~5에 없던 특징만 넣었다.
#   - 신체계측·혈압·시력·청력은 가로 표: 항목명이 윗줄, 값이 아랫줄 (이름과 값이 다른 줄)
#   - 단위는 항목명 괄호 안 ("신장(cm)")
#   - 혈액·소변 검사는 표 없이 "이름 ······ 값 단위 [판정] (기준 … / 지난 검진 …)" 한 줄
#   - 판정 어휘 양호/관찰/진료권고
#   - 쪽 꼬리말(주소 "가상로 123, 4층", "1 / 2쪽")이 문서 중간에 끼어 있다

GUIDE_LABELS = {"normal": "양호", "borderline": "관찰", "abnormal": "진료권고"}
GUIDE_TABLE_CODES = {"HEIGHT", "WEIGHT", "WAIST_CIRCUMFERENCE", "BMI", "SYSTOLIC_BP", "DIASTOLIC_BP",
                     "VISION_LEFT", "VISION_RIGHT", "HEARING_LEFT", "HEARING_RIGHT"}


def _guide_line(name: str, m: Measured, judgment: str | None) -> str:
    leader = "·" * max(3, 14 - len(name))
    line = f"{name} {leader} {m.value}" + (f" {m.unit}" if m.unit else "") + (f" [{judgment}]" if judgment else "")
    notes = ([f"기준 {m.reference}"] if m.reference else []) + [f"지난 검진 {m.previous}"]
    return f"{line} ({' / '.join(notes)})"


def guide_report(rng: random.Random, subject: Subject, items: list[Measured], labels: dict) -> tuple:
    codes = _by_code(items)
    expected: list[dict] = []

    def label(m: Measured) -> str | None:
        return GUIDE_LABELS[m.band] if m.band else None

    # 가로 표: 항목명(단위) | 결과 | 판정
    header, values, judgments = ["구분"], ["결과"], ["판정"]
    for code in ("HEIGHT", "WEIGHT", "WAIST_CIRCUMFERENCE", "BMI"):
        m = codes[code]
        header.append(f"{m.names[0]}({m.unit})")
        values.append(m.value)
        judgments.append(label(m) or "")
        expected.append(_expected_item(m, m.names[0], unit=m.unit, judgment=label(m)))
    sbp, dbp = codes["SYSTOLIC_BP"], codes["DIASTOLIC_BP"]
    header.append("혈압(mmHg)")
    values.append(f"{sbp.value}/{dbp.value}")
    judgments.append(label(sbp) or "")
    expected.append(_expected_item(sbp, "혈압", unit="mmHg", judgment=label(sbp)))
    expected.append(_expected_item(dbp, "혈압", unit="mmHg", judgment=label(sbp)))
    body_table = Table(header, [values, judgments])

    senses_table = None
    if "VISION_LEFT" in codes:
        names = (("VISION_LEFT", "시력(좌)", "시력"), ("VISION_RIGHT", "시력(우)", "시력"),
                 ("HEARING_LEFT", "청력(좌)", "청력"), ("HEARING_RIGHT", "청력(우)", "청력"))
        senses_table = Table(["구분", *(printed for _, printed, _ in names)],
                             [["결과", *(codes[code].value for code, _, _ in names)]])
        for code, _, raw_name in names:
            expected.append(_expected_item(codes[code], raw_name))

    # 혈액·소변 검사와 추가 검사: 표 없이 한 줄씩
    lab_lines, other_lines = [], []
    for m in items:
        if m.code in GUIDE_TABLE_CODES or m.code == "CHEST_XRAY":
            continue
        name = m.names[0]
        (other_lines if m.code == "OTHER" else lab_lines).append(Para(_guide_line(name, m, label(m))))
        expected.append(_expected_item(m, name, unit=m.unit, reference=m.reference, judgment=label(m)))

    xray = codes["CHEST_XRAY"]
    expected.append(_expected_item(xray, "흉부 촬영", judgment=label(xray)))
    xray_line = Para(f"흉부 촬영 {'·' * 10} {xray.value}" + (f" [{label(xray)}]" if label(xray) else ""))

    sex_ko = "남" if subject.sex == "MALE" else "여"
    d = subject.exam_date
    footer = f"{FAKE_CENTER} · 가상시 가상구 가상로 123, 4층"
    blocks: list[Block] = [
        Heading("건강검진 결과 안내문"),
        Para(SYNTHETIC_NOTICE),
        Para(f"{subject.masked_name} 님 ({sex_ko}, 만 {subject.age}세) · 수검일 {d.year}년 {d.month}월 {d.day}일"),
        Heading("■ 신체 계측"),
        body_table,
        *([Heading("■ 시력·청력"), senses_table] if senses_table else []),
        Heading("■ 혈액·소변 검사"),
        *lab_lines,
        Para(f"{footer} · 1 / 2쪽"),
        *([Heading("■ 추가 검사"), *other_lines] if other_lines else []),
        Heading("■ 영상 검사"),
        xray_line,
        Para("※ [관찰]·[진료권고] 항목은 의료진과 상담하시기 바랍니다."),
        Para(f"{footer} · 2 / 2쪽"),
    ]
    meta = {"distractors": ["transposed_table", "unit_in_name", "leader_lines", "guide_vocabulary",
                            "page_footer_numbers"], "age_printed": True}
    return blocks, _expected(subject, "UNKNOWN", expected, age_printed=True), meta


# 일반화 시험용. 검증 규칙을 만들 때 쓰지 않는다
HELDOUT_TEMPLATES = {
    "lab": lab_report,      # 검사실 결과지형 (heldout-v1, 검증 v2.2 시험에 사용. 결과를 봤으므로 이후로는 '본 양식')
    "trend": trend_report,  # 3개년 추이표형 (heldout-v2, 검증 v2.3 시험에 사용. v2.4 규칙의 근거라 이후로는 '본 양식')
    "guide": guide_report,  # 안내문형 (heldout-v3, 검증 v2.4 전에 생성)
}
