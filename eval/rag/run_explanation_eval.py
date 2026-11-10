"""해설 평가 (ADR 0007 9절의 측정 2·3·4). 5단계 개선과 비교할 기준선이다.

합성 샘플 결과지마다 /api/demo/samples/{id}/explain을 부른다 (추출 + 판정 + 검색 + 해설, Haiku 호출 2회).
Haiku는 출력이 실행마다 흔들리므로 같은 보고서를 여러 번 돌려 범위로 본다.

지표
- 해설 검증: 문장 통과율(검증을 통과한 문장 / LLM이 낸 문장), 잘못된 인용 수, 근거 없는 숫자로 버린 문장 수,
  인용 없는 문장 수, 진단 단정·치료 지시 표현 수(막지 않고 기록만), 근거 없음 항목 수, 생성 실패·잘림 수
- 진료 권고 재현율: 정답 값을 같은 판정기준(backend/report/.../criteria/*.json)으로 판정했을 때 질환의심인 항목 중,
  해설에 진료 권고가 붙은 비율 (추출 오류·대상 상한까지 포함한 끝에서 끝까지의 값). 권고 정밀도도 함께 본다
- 고지문 포함률, 지연 (검색·생성·전체)

사용법 (eval/ 폴더에서, 앱이 떠 있어야 한다): .venv/bin/python -m rag.run_explanation_eval [--runs 3] [--ids std-001,...]
"""
from __future__ import annotations

import argparse
import json
import re
import urllib.request
from datetime import datetime
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent.parent
ROOT = EVAL_DIR.parent
DATA = ROOT / "data" / "synthetic" / "extraction" / "v1"
CRITERIA = ROOT / "backend" / "report" / "src" / "main" / "resources" / "criteria" / "general-screening-2026-6.json"
RESULTS = EVAL_DIR / "results" / "rag"
DEFAULT_IDS = "std-001,std-002,std-003,std-004,sum-001,sum-002,sum-003,nar-001,nar-002,nar-003"
NUMBER = re.compile(r"[-+]?\d+(\.\d+)?")


def judge(criteria: dict, code: str, value: str, sex: str | None) -> str:
    """ScreeningCriteria(Java)와 같은 규칙. 정답 값의 판정을 독립적으로 계산한다"""
    item = criteria["items"].get(code)
    if item is None or value is None:
        return "NOT_JUDGED"
    if item.get("textBands"):
        for band in item["textBands"]:
            if re.search(band["pattern"], value.strip()):
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--ids", default=DEFAULT_IDS)
    args = parser.parse_args()
    criteria = json.loads(CRITERIA.read_text(encoding="utf-8"))
    ids = args.ids.split(",")
    runs = []
    for run in range(1, args.runs + 1):
        totals = {k: 0 for k in ("reports", "targets", "generated", "kept", "invalidCitations", "uncited", "ungrounded",
                                 "forbidden", "noEvidence", "llmErrors", "salvaged", "disclaimer", "expectedSuspect",
                                 "advisedCorrect", "advised", "retrievalMs", "llmMs", "totalMs")}
        for report_id in ids:
            expected = json.loads((DATA / f"{report_id}.expected.json").read_text(encoding="utf-8"))
            request = urllib.request.Request(f"{args.api}/api/demo/samples/{report_id}/explain", method="POST")
            with urllib.request.urlopen(request, timeout=300) as response:
                x = json.load(response)["explanation"]
            m = x["metrics"]
            expected_suspect = {it["code"] for it in expected["items"]
                                if it["code"] != "OTHER" and judge(criteria, it["code"], it["value"], expected.get("sex")) == "SUSPECT"}
            advised = {it["code"] for it in x["items"] if it.get("careAdvice")}
            totals["reports"] += 1
            totals["targets"] += m["targets"]
            totals["generated"] += m["sentencesGenerated"]
            totals["kept"] += m["sentencesKept"]
            totals["invalidCitations"] += m["invalidCitations"]
            totals["uncited"] += m["uncitedSentences"]
            totals["ungrounded"] += m["ungroundedNumberSentences"]
            totals["forbidden"] += m["forbiddenHits"]
            totals["noEvidence"] += sum(1 for it in x["items"] if it["noEvidence"])
            totals["llmErrors"] += 1 if m.get("llmError") else 0
            totals["salvaged"] += 1 if m.get("salvagedItems", -1) >= 0 else 0
            totals["disclaimer"] += 1 if x.get("disclaimer") else 0
            totals["expectedSuspect"] += len(expected_suspect)
            totals["advisedCorrect"] += len(expected_suspect & advised)
            totals["advised"] += len(advised)
            for key in ("retrievalMs", "llmMs", "totalMs"):
                totals[key] += m[key]
            print(f"[{run}] {report_id}: 대상 {m['targets']} | 문장 {m['sentencesKept']}/{m['sentencesGenerated']} | "
                  f"권고 {len(expected_suspect & advised)}/{len(expected_suspect)} (권고 {len(advised)}) | "
                  f"근거 없음 {sum(1 for it in x['items'] if it['noEvidence'])} | 금지 {m['forbiddenHits']} | "
                  f"{m['totalMs'] / 1000:.1f}초" + (f" | 실패 {m['llmError']}" if m.get("llmError") else ""))
        n = totals["reports"]
        summary = {
            "run": run, "reports": n,
            "sentencePassRate": round(totals["kept"] / totals["generated"], 3) if totals["generated"] else None,
            "invalidCitations": totals["invalidCitations"], "uncitedSentences": totals["uncited"],
            "ungroundedNumberSentences": totals["ungrounded"], "forbiddenHits": totals["forbidden"],
            "noEvidenceItems": totals["noEvidence"], "llmErrors": totals["llmErrors"], "salvaged": totals["salvaged"],
            "careAdviceRecall": round(totals["advisedCorrect"] / totals["expectedSuspect"], 3) if totals["expectedSuspect"] else None,
            "careAdvicePrecision": round(totals["advisedCorrect"] / totals["advised"], 3) if totals["advised"] else None,
            "expectedSuspectItems": totals["expectedSuspect"], "disclaimerRate": round(totals["disclaimer"] / n, 3),
            "avgTargets": round(totals["targets"] / n, 1), "avgRetrievalMs": round(totals["retrievalMs"] / n),
            "avgLlmMs": round(totals["llmMs"] / n), "avgTotalMs": round(totals["totalMs"] / n),
        }
        runs.append(summary)
        print(json.dumps(summary, ensure_ascii=False))
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = RESULTS / f"{datetime.now():%Y%m%d-%H%M}_explanation_runs{args.runs}.json"
    out.write_text(json.dumps({"ids": ids, "runs": runs}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
