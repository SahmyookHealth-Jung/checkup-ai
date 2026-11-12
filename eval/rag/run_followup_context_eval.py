"""후속 질문 문맥 평가 (ADR 0011 5절 1단계). LLM을 부르지 않는다.

앱의 문맥 만들기(FollowupContextBuilder, POST /api/eval/followup/context)가 고른 근거에 정답 절이 들어오는지 잰다.
결과지 맥락은 해설 평가와 같은 고정 추출(heldout30-v2.6)을 쓴다.

조건
- selected-<예산>: 정답셋의 itemCodes를 "화면에서 고른 항목"으로 넘긴다 (항목 고름)
- inferred-<예산>: 항목을 넘기지 않는다. 질문의 항목 이름 → 결과지 주의 항목 순으로 앱이 정한다 (항목 안 고름)
- bm25-inferred-<예산>: 항목 안 고름과 같은 범위에서, 순위만 글자 2-gram BM25로 매긴다. 앱에는 넣지 않는 조건이다.
  ADR 0010의 "하이브리드 전후 비교" 약속을 여기서 지킨다 (ADR 0011 4절)

지표 (답 가능 문항)
- full: 정답 절이 문맥에 모두 들어온 문항 수 (주 지표, 예산 결정 규칙)
- coverage: 정답 절 중 문맥에 들어온 비율의 평균
- chars: 문맥 글자 수(고정 근거 포함)의 중앙값과 최대값
예산 결정 규칙 (ADR 0011 2절 5): 개발 몫 inferred 조건에서 full이 많은 예산, 같으면 작은 예산.

사용법 (eval/ 폴더에서, 앱이 떠 있고 청크를 적재한 상태):
    .venv/bin/python -m rag.run_followup_context_eval --budgets 8000,16000 --label context
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import urllib.request
from collections import Counter

import run_record
from rag.followup_evidence import GOLD, unit_ids_sha
from run_record import EVAL_DIR

FROZEN = EVAL_DIR / "frozen" / "heldout30-v2.6"
HUGE = 10_000_000  # 범위 전체를 받을 때의 예산 (BM25 조건)


def post(api: str, body: dict) -> dict:
    request = urllib.request.Request(f"{api}/api/eval/followup/context", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def bigrams(text: str) -> list[str]:
    text = "".join(text.split())
    return [text[i:i + 2] for i in range(len(text) - 1)]


def bm25_rank(query: str, units: list[dict], k1: float = 1.2, b: float = 0.75) -> list[dict]:
    """범위 안 절들에 대해 글자 2-gram BM25 점수를 매겨 높은 순으로 돌려준다 (형태소 분석 없음)"""
    docs = [Counter(bigrams(u["text"])) for u in units]
    lengths = [sum(d.values()) for d in docs]
    avg = statistics.mean(lengths) if lengths else 1
    n = len(docs)
    df = Counter(term for d in docs for term in d)
    terms = set(bigrams(query))
    scored = []
    for unit, doc, length in zip(units, docs, lengths):
        score = 0.0
        for term in terms:
            tf = doc.get(term, 0)
            if tf:
                idf = math.log(1 + (n - df[term] + 0.5) / (df[term] + 0.5))
                score += idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * length / avg))
        scored.append((score, unit))
    return [u for _, u in sorted(scored, key=lambda x: -x[0])]


def pack(units: list[dict], budget: int) -> list[dict]:
    out, used = [], 0
    for unit in units:
        if used + len(unit["text"]) <= budget:
            out.append(unit)
            used += len(unit["text"])
    return out


def measure(context_units: list[dict], expected: set[str]) -> dict:
    ids = {u["id"] for u in context_units}
    return {"full": expected <= ids, "coverage": len(expected & ids) / len(expected) if expected else None,
            "chars": sum(len(u["text"]) for u in context_units), "units": sorted(ids)}


def summarize(rows: list[dict]) -> dict:
    if not rows:
        return {}
    chars = [r["chars"] for r in rows]
    return {"n": len(rows), "full": sum(1 for r in rows if r["full"]),
            "coverage": round(statistics.mean(r["coverage"] for r in rows), 3),
            "charsMedian": int(statistics.median(chars)), "charsMax": max(chars),
            "itemSource": dict(Counter(r["itemSource"] for r in rows))}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--budgets", default="8000,16000")
    parser.add_argument("--label", default="context")
    args = parser.parse_args()
    git = run_record.git_state()
    if git["dirty"]:
        print("주의: 커밋하지 않은 변경이 있다. 기록에 dirty=true로 남는다 (비교 수치로 쓰지 않는다)")
    budgets = [int(b) for b in args.budgets.split(",")]
    gold = [json.loads(line) for line in GOLD.read_text(encoding="utf-8").splitlines() if line.strip()]
    answerable = [g for g in gold if g["category"] == "answerable"]
    conditions = [f"{mode}-{b}" for mode in ("selected", "inferred", "bm25-inferred") for b in budgets]
    rows = []
    for g in answerable:
        report = json.loads((FROZEN / f"{g['report']}.json").read_text(encoding="utf-8"))["report"] if g.get("report") else None
        expected = set(g["expectedSections"] or [])
        base = {"report": report, "question": g["question"]}
        whole = post(args.api, base | {"budgetChars": HUGE})  # 항목 안 고름, 범위 전체 (BM25 조건의 재료)
        for budget in budgets:
            for mode in ("selected", "inferred"):
                body = base | {"budgetChars": budget} | ({"itemCodes": g["itemCodes"]} if mode == "selected" else {})
                ctx = post(args.api, body)
                rows.append({"id": g["id"], "split": g["split"], "author": g["author"], "condition": f"{mode}-{budget}",
                             "itemSource": ctx["itemSource"], "textItems": ctx["textItems"],
                             **measure(ctx["fixedUnits"] + ctx["rankedUnits"], expected)})
            ranked = pack(bm25_rank(whole["query"], whole["rankedUnits"]), budget)
            rows.append({"id": g["id"], "split": g["split"], "author": g["author"], "condition": f"bm25-inferred-{budget}",
                         "itemSource": whole["itemSource"], "textItems": whole["textItems"],
                         **measure(whole["fixedUnits"] + ranked, expected)})
    summary = {}
    for cond in conditions:
        part = [r for r in rows if r["condition"] == cond]
        summary[cond] = {"dev": summarize([r for r in part if r["split"] == "dev"]),
                         "test": summarize([r for r in part if r["split"] == "test"]),
                         "user": summarize([r for r in part if r["author"] == "user"]),
                         "claude": summarize([r for r in part if r["author"] == "claude"])}
    dev = {b: summary[f"inferred-{b}"]["dev"]["full"] for b in budgets}
    chosen = sorted(budgets, key=lambda b: (-dev[b], b))[0]
    summary["budgetDecision"] = {"rule": "개발 몫 inferred 조건의 full이 많은 예산, 같으면 작은 예산", "devFull": dev,
                                 "chosen": chosen}
    print(f"{'조건':<22} {'개발 full/coverage/글자 중앙':<28} {'시험 full/coverage/글자 중앙':<28}")
    for cond in conditions:
        d, t = summary[cond]["dev"], summary[cond]["test"]
        print(f"{cond:<22} {d['full']:>2}/{d['n']} {d['coverage']:.3f} {d['charsMedian']:>6}      "
              f"{t['full']:>2}/{t['n']} {t['coverage']:.3f} {t['charsMedian']:>6}")
    print("예산 결정:", summary["budgetDecision"])
    run_id = run_record.new_run_id("followup-context", args.label)
    out = run_record.save(run_id, "followup-context", "rag", label=args.label,
                          dataset={"name": "followup-v1", "source": str(GOLD.relative_to(EVAL_DIR)),
                                   "reports": len(answerable), "frozen": FROZEN.name, "unitsSha256": unit_ids_sha()},
                          config={"budgets": budgets, "conditions": conditions}, summary=summary,
                          headline={"chosenBudget": chosen, **{f"devFull:{c}": summary[c]["dev"]["full"] for c in conditions}},
                          details=rows)
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
