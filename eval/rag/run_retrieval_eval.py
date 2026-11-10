"""해설 근거 검색 평가 (ADR 0007 9절의 측정 1). 5단계(하이브리드·필터·리랭킹)와 비교할 기준선이다.

정답셋 eval/rag/retrieval_queries.jsonl은 검색을 처음 돌려 보기 전에 만들었다 (결과를 보고 정답을 고치지 않는다).
질의는 해설 서비스가 만드는 형식 그대로다: "{검진 항목명} {판정 구분}".
- primary: 그 항목의 판정기준표 행 (해설의 기준 근거). 반드시 찾아야 한다
- relevant: primary + 같은 내용을 설명하는 포털 절·판정 구분 정의

지표: hit@1, hit@k(relevant 중 하나라도 상위 k개 안), primary hit@k, MRR(처음 맞힌 순위의 역수 평균), 평균 검색 지연.

사용법 (eval/ 폴더에서, 앱이 떠 있어야 한다): .venv/bin/python -m rag.run_retrieval_eval [--k 4]
"""
from __future__ import annotations

import argparse
import json
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent.parent
QUERIES = EVAL_DIR / "rag" / "retrieval_queries.jsonl"
RESULTS = EVAL_DIR / "results" / "rag"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--k", type=int, default=4)
    args = parser.parse_args()
    queries = [json.loads(line) for line in QUERIES.read_text(encoding="utf-8").splitlines() if line.strip()]
    rows = []
    for q in queries:
        url = f"{args.api}/api/guidelines/search?" + urllib.parse.urlencode({"q": q["query"], "k": args.k})
        with urllib.request.urlopen(url, timeout=60) as response:
            result = json.load(response)
        ids = [chunk["id"] for chunk in result["chunks"]]
        rank = next((i + 1 for i, chunk_id in enumerate(ids) if chunk_id in q["relevant"]), None)
        primary_rank = ids.index(q["primary"]) + 1 if q["primary"] in ids else None
        rows.append({"query": q["query"], "rank": rank, "primaryRank": primary_rank, "latencyMs": result["latencyMs"],
                     "top": [(chunk["id"], round(chunk["score"], 3)) for chunk in result["chunks"]]})
        print(f"{q['query']:<28} 첫 정답 {rank or '-':>2}위 | 기준표 행 {primary_rank or '-':>2}위 | "
              f"1위 {ids[0] if ids else '-'} ({result['chunks'][0]['score']:.3f})" if ids else q["query"])
    n = len(rows)
    summary = {
        "runAt": datetime.now().isoformat(timespec="seconds"), "k": args.k, "queries": n,
        "hitAt1": round(sum(1 for r in rows if r["rank"] == 1) / n, 3),
        f"hitAt{args.k}": round(sum(1 for r in rows if r["rank"]) / n, 3),
        f"primaryHitAt{args.k}": round(sum(1 for r in rows if r["primaryRank"]) / n, 3),
        "mrr": round(sum(1 / r["rank"] for r in rows if r["rank"]) / n, 3),
        "avgLatencyMs": round(sum(r["latencyMs"] for r in rows) / n),
    }
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = RESULTS / f"{datetime.now():%Y%m%d-%H%M}_retrieval_k{args.k}.json"
    out.write_text(json.dumps({"summary": summary, "rows": rows}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
