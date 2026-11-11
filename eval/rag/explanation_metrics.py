"""해설 평가 지표 (ADR 0008 3절). 모델을 부르지 않는 순수 함수만 둔다 (단위 테스트: test_explanation_metrics.py).

지표를 세 묶음으로 나눈다. 묶음마다 무엇의 품질인지가 다르다.
- 해설 LLM: 문장 통과, 지운 인용, 근거 없는 숫자, 인용 없음, 금지 표현, 근거 없음 항목, 생성 실패. (의미 충실도는 채점기 단계에서 더한다)
- 끝에서 끝까지: 진료 권고 재현율. 놓친 항목은 원인을 하나로 분류한다.
    extraction   추출이 항목을 못 뽑았거나, 값·성별을 다르게 뽑아 판정이 달라졌다
    needsReview  값이 가능 범위 밖이라 확인 필요 항목으로 갔다 (화면은 고정 문구로 원본 확인과 진료를 권고한다)
    judgment     추출 값·성별이 정답과 같은데 판정 코드(Java)가 다르게 판정했다 (판정 코드 결함)
    maxItems     질환의심으로 판정했지만 해설 대상 상한(max-items)에서 잘렸다
    other        위 어디에도 해당하지 않는다 (조사 대상)
- 코드가 보장하는 값: 고지문 포함 건수. 진료 권고 문구는 코드 상수라 따로 재지 않는다.
"""
from __future__ import annotations

import re

from extraction.metrics import normalize_value

NUMBER = re.compile(r"[-+]?\d+(\.\d+)?")
MISS_CAUSES = ("extraction", "needsReview", "judgment", "maxItems", "other")


def judge(criteria: dict, code: str, value: str | None, sex: str | None) -> str:
    """ScreeningCriteria(Java)와 같은 기준표 규칙. 정답 값의 판정을 독립적으로 계산한다.
    깨끗한 값(숫자만, 대표 단위, 기준 파일의 표기)만 다룬다. Java의 단위 확인과 표기 정리(높음·낮음 표시, 쉼표)는 옮기지 않았다.
    표기 변형의 정답은 변형 전의 깨끗한 값으로 낸다 (notation_metrics.py)"""
    item = criteria["items"].get(code)
    if item is None or value is None:
        return "NOT_JUDGED"
    if item.get("textBands"):
        for band in item["textBands"]:
            if re.search(band["pattern"], value.strip().lower()):
                return band["judgment"]
        return "NOT_JUDGED"
    if not NUMBER.fullmatch(value.strip()):
        return "NOT_JUDGED"
    bands = item["bands"] if "bands" in item else (item["bySex"].get(sex) if sex else None)
    if bands is None:
        return "NOT_JUDGED"
    x = float(value)
    for band in bands:
        if (band.get("min") is None or x >= band["min"]) and (band.get("minExclusive") is None or x > band["minExclusive"]) \
                and (band.get("max") is None or x < band["max"]) and (band.get("maxInclusive") is None or x <= band["maxInclusive"]):
            return band["judgment"]
    return "NOT_JUDGED"


def expected_suspect(criteria: dict, expected: dict) -> dict[str, str]:
    """정답 기준 질환의심 항목: 코드 → 정답 값"""
    return {it["code"]: it["value"] for it in expected["items"]
            if it["code"] != "OTHER" and judge(criteria, it["code"], it["value"], expected.get("sex")) == "SUSPECT"}


def miss_cause(code: str, expected_value: str, expected_sex: str | None, report: dict, needs_review: list[dict],
               explanation: dict) -> str:
    """진료 권고를 놓친 질환의심 항목 하나의 원인 (모듈 설명의 분류)"""
    if any(r.get("code") == code for r in needs_review):
        return "needsReview"
    judged = next((j for j in explanation.get("judgments") or [] if j["code"] == code), None)
    if judged is None:
        return "extraction"
    if judged["judgment"] != "SUSPECT":
        same_value = normalize_value(judged.get("value")) == normalize_value(expected_value)
        same_sex = (report.get("sex") or "UNKNOWN") == (expected_sex or "UNKNOWN")
        return "judgment" if same_value and same_sex else "extraction"
    if not any(it["code"] == code for it in explanation.get("items") or []):
        return "maxItems"
    return "other"


def assess_report(criteria: dict, expected: dict, report: dict, needs_review: list[dict], explanation: dict) -> dict:
    """보고서 하나의 지표. 합계는 summarize()가 낸다"""
    m = explanation["metrics"]
    suspect = expected_suspect(criteria, expected)
    advised = {it["code"] for it in explanation["items"] if it.get("careAdvice")}
    misses = [{"code": code, "cause": miss_cause(code, value, expected.get("sex"), report, needs_review, explanation)}
              for code, value in sorted(suspect.items()) if code not in advised]
    return {
        "targets": m["targets"], "targetsWithEvidence": m["targetsWithEvidence"],
        "sentencesGenerated": m["sentencesGenerated"], "sentencesKept": m["sentencesKept"],
        "invalidCitations": m["invalidCitations"], "uncitedSentences": m["uncitedSentences"],
        "ungroundedNumberSentences": m["ungroundedNumberSentences"], "forbiddenHits": m["forbiddenHits"],
        "noEvidenceItems": sum(1 for it in explanation["items"] if it["noEvidence"]),
        "templatedItems": m.get("templatedItems", 0),
        "llmError": m.get("llmError"), "salvaged": m.get("salvagedItems", -1) >= 0,
        "disclaimer": bool(explanation.get("disclaimer")),
        "expectedSuspect": sorted(suspect), "advised": sorted(advised),
        "misses": misses, "falseAdvice": sorted(advised - suspect.keys()),
        "retrievalMs": m["retrievalMs"], "llmMs": m["llmMs"], "totalMs": m["totalMs"],
    }


def summarize(rows: list[dict]) -> dict:
    """한 실행(보고서 여러 개)의 지표. 건수가 작은 값은 비율 대신 건수로 둔다 (ADR 0008 3절)"""
    total = lambda key: sum(r[key] for r in rows)
    n = len(rows)
    expected = sum(len(r["expectedSuspect"]) for r in rows)
    advised = sum(len(r["advised"]) for r in rows)
    advised_correct = expected - sum(len(r["misses"]) for r in rows)
    causes = {cause: sum(1 for r in rows for miss in r["misses"] if miss["cause"] == cause) for cause in MISS_CAUSES}
    generated = total("sentencesGenerated")
    return {
        "reports": n,
        "explanationLlm": {
            "sentencesGenerated": generated, "sentencesKept": total("sentencesKept"),
            "sentencePassRate": round(total("sentencesKept") / generated, 3) if generated else None,
            "invalidCitations": total("invalidCitations"), "uncitedSentences": total("uncitedSentences"),
            "ungroundedNumberSentences": total("ungroundedNumberSentences"), "forbiddenHits": total("forbiddenHits"),
            "noEvidenceItems": total("noEvidenceItems"),
            "llmErrors": sum(1 for r in rows if r["llmError"]), "salvaged": sum(1 for r in rows if r["salvaged"]),
            "templatedItems": sum(r.get("templatedItems", 0) for r in rows),
        },
        "endToEnd": {
            "expectedSuspect": expected, "advisedCorrect": advised_correct,
            "careAdviceRecall": round(advised_correct / expected, 3) if expected else None,
            "advised": advised, "falseAdvice": sum(len(r["falseAdvice"]) for r in rows),
            "careAdvicePrecision": round(advised_correct / advised, 3) if advised else None,
            "missCauses": causes,
        },
        "codeGuaranteed": {"disclaimer": f"{sum(1 for r in rows if r['disclaimer'])}/{n}"},
        "latency": {key: round(total(key) / n) for key in ("retrievalMs", "llmMs", "totalMs")},
        "avgTargets": round(total("targets") / n, 1),
    }


def compact_output(explanation: dict) -> list[dict]:
    """채점기(5단계 3번)가 다시 읽을 해설 출력. 인용 번호를 청크 id로 바꾸고 근거 원문은 뺀다
    (포털 글은 공공누리 제4유형이라 저장소에 올리지 않는다. 원문은 청크 파일에서 id로 다시 찾는다)"""
    chunk_by_number = {c["number"]: c["chunkId"] for c in explanation.get("citations") or []}
    # evidence: 이 항목에 허락된 근거 청크 id. 조건을 바꿨을 때 근거 목록이 바뀐 항목을 가려낸다 (ADR 0008 4절 4번)
    # origin: LLM 문장인지 코드 문구인지 (5단계 이전 결과에는 없어서 LLM으로 본다)
    return [{"code": it["code"], "value": it["value"], "unit": it.get("unit"), "judgment": it["judgment"],
             "criterion": it.get("criterion"), "noEvidence": it["noEvidence"],
             "evidence": it.get("evidenceChunkIds"),
             "sentences": [{"text": s["text"], "sources": [chunk_by_number[n] for n in s["citations"]],
                            "origin": s.get("origin") or "LLM"}
                           for s in it["sentences"]]}
            for it in explanation["items"]]
