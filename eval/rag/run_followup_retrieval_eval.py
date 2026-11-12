"""후속 질문 경로의 검색 평가 (ADR 0010 순서 6). 벡터 검색 기준선을 재고, 뒤에서 하이브리드·리랭킹과 비교한다.

정답셋: eval/followup/followup-v1.jsonl (질문은 문서를 보지 않고 먼저 썼고, 근거 절은 문서를 넣은 뒤 붙였다).
- 정답 단위는 절이다(고시는 행). 검색한 청크가 정답 절에 속하면 맞힌 것으로 센다 (rag.followup_evidence.unit_of)
- 답 가능 문항: 정답 절을 찾았는가
  - hit@k: 상위 k개 청크 안에 정답 절이 하나라도 있다
  - recall@k: 정답 절 중 상위 k개 안에서 찾은 비율 (문항 평균)
  - MRR: 처음 정답 절이 나온 순위의 역수 평균 (상위 kmax 밖이면 0)
- 답 불가·거절·악성: 검색이 "근거 없음"을 가를 수 있는가. 1위 유사도의 분포와, 임계값(설정 0.5)에서
  답 가능 문항이 근거를 잃는 비율과 답 불가 문항이 근거를 얻는 비율을 함께 본다
- 개발/시험, 작성자(사용자/Claude)별로 따로 낸다 (ADR 0010 순서 4의 약속)

질의 만드는 방식 (--query)
- question: 질문 문장만
- question+items: 질문 + 질문 항목의 한국어 이름 (예: "결과값이 어려운데 뭔가요 신사구체여과율(eGFR)")

사용법 (eval/ 폴더에서, 앱이 떠 있고 청크를 적재한 상태):
    .venv/bin/python -m rag.run_followup_retrieval_eval --query question --label vector-question
"""
from __future__ import annotations

import argparse
import json
import statistics
import urllib.parse
import urllib.request

import run_record
from rag.build_calibration_set import item_names
from rag.followup_evidence import CRITERIA_DOC, GOLD, unit_ids_sha
from run_record import EVAL_DIR

KS = (1, 4, 8)
KMAX = max(KS)


def unit_of_hit(chunk: dict) -> str:
    """검색 결과 청크(API 응답)가 속한 정답 단위. rag.followup_evidence.unit_of와 같은 규칙"""
    m = chunk["metadata"]
    return chunk["id"] if m.get("doc_id") == CRITERIA_DOC else f"{m.get('doc_id')}#{m.get('section')}"


def build_query(row: dict, mode: str, names: dict[str, str]) -> str:
    if mode == "question":
        return row["question"]
    items = " ".join(names.get(code, code) for code in row.get("itemCodes") or [])
    return f"{row['question']} {items}".strip()


def search(api: str, query: str, k: int) -> dict:
    url = f"{api}/api/guidelines/search?" + urllib.parse.urlencode({"q": query, "k": k})
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def summarize(rows: list[dict], threshold: float) -> dict:
    answerable = [r for r in rows if r["category"] == "answerable"]
    out: dict = {"questions": len(rows), "answerable": len(answerable)}
    if answerable:
        for k in KS:
            out[f"hitAt{k}"] = round(sum(1 for r in answerable if r["firstRank"] and r["firstRank"] <= k) / len(answerable), 3)
            out[f"recallAt{k}"] = round(statistics.mean(r[f"recallAt{k}"] for r in answerable), 3)
        out["mrr"] = round(statistics.mean(1 / r["firstRank"] if r["firstRank"] else 0 for r in answerable), 3)
    # 1위 유사도: 범주별 분포와 임계값에서 갈리는 정도
    by_cat = {}
    for cat in ("answerable", "unanswerable", "refuse", "malicious"):
        scores = [r["topScore"] for r in rows if r["category"] == cat]
        if scores:
            by_cat[cat] = {"n": len(scores), "min": round(min(scores), 3), "median": round(statistics.median(scores), 3),
                           "max": round(max(scores), 3), f"belowThreshold": sum(1 for s in scores if s < threshold)}
    out["topScore"] = by_cat
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--query", choices=["question", "question+items"], default="question")
    parser.add_argument("--threshold", type=float, default=0.5, help="근거 없음 임계값 (설정 min-similarity와 같게)")
    parser.add_argument("--label", default="vector")
    args = parser.parse_args()
    git = run_record.git_state()
    if git["dirty"]:
        print("주의: 커밋하지 않은 변경이 있다. 기록에 dirty=true로 남는다 (비교 수치로 쓰지 않는다)")
    names = item_names()
    gold = [json.loads(line) for line in GOLD.read_text(encoding="utf-8").splitlines() if line.strip()]
    rows = []
    for g in gold:
        query = build_query(g, args.query, names)
        result = search(args.api, query, KMAX)
        units = [unit_of_hit(c) for c in result["chunks"]]
        expected = set(g.get("expectedSections") or [])
        first = next((i + 1 for i, u in enumerate(units) if u in expected), None)
        row = {"id": g["id"], "split": g["split"], "author": g["author"], "category": g["category"], "query": query,
               "expected": sorted(expected), "firstRank": first,
               "topScore": result["chunks"][0]["score"] if result["chunks"] else 0.0,
               "top": [(c["id"], round(c["score"], 3)) for c in result["chunks"]], "latencyMs": result["latencyMs"]}
        for k in KS:
            row[f"recallAt{k}"] = len(expected & set(units[:k])) / len(expected) if expected else None
        rows.append(row)
        if g["category"] == "answerable":
            print(f"{g['id']} {g['split'][:3]} 첫 정답 {first or '-':>2}위 | 1위 {units[0] if units else '-'} "
                  f"({row['topScore']:.3f}) | {g['question'][:30]}")
    summary = {"all": summarize(rows, args.threshold),
               **{f"split:{s}": summarize([r for r in rows if r["split"] == s], args.threshold) for s in ("dev", "test")},
               **{f"author:{a}": summarize([r for r in rows if r["author"] == a], args.threshold) for a in ("user", "claude")},
               "avgLatencyMs": round(statistics.mean(r["latencyMs"] for r in rows))}
    print(json.dumps(summary["all"], ensure_ascii=False, indent=1))
    manifest = json.loads((GOLD.parent / "followup-v1.manifest.json").read_text(encoding="utf-8"))
    run_id = run_record.new_run_id("followup-retrieval", args.label)
    out = run_record.save(
        run_id, "followup-retrieval", "rag", label=args.label,
        dataset={"name": "followup-v1", "source": str(GOLD.relative_to(EVAL_DIR)), "reports": len(rows),
                 "unitsSha256": unit_ids_sha(), "evidenceUnitsSha256": (manifest.get("evidence") or {}).get("unitsSha256")},
        config={"retrieval": "vector", "query": args.query, "kmax": KMAX, "threshold": args.threshold},
        summary=summary,
        headline={k: summary["all"].get(k) for k in ("hitAt4", "recallAt4", "mrr")}, details=rows)
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
