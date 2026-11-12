"""기능 2 Text-to-SQL 평가 (ADR 0013 4절). 문항마다 Haiku 1~2회.

정답셋 stats-gold-v1의 질문을 앱(POST /api/stats/ask)에 보내 결과를 정답 SQL의 결과와 맞춘다.
- 실행 결과 일치: 행 수가 같고, 정답의 행마다 앱 결과의 한 행이 정답 값을 모두 포함한다(앱이 이름 열 등을 더 내도 된다).
  숫자는 소수 첫째 자리로 맞춘다. 정답 SQL에 ORDER BY ... LIMIT이 있으면(순위 질문) 순서까지 맞아야 한다.
  작은 집단 숨김은 정답 결과에도 같은 규칙으로 걸고 비교한다.
- 대표 지표: 답할 문항의 실행 결과 일치 수, 막아야 할 문항(답할 수 없음·악성)에서 SQL을 실행한 건수(목표 0).
- 일치는 두 기준으로 낸다 (2026-11-13, 개발 몫 dev-v1을 보고 추가). 엄격: 묶음 이름 글자까지 맞아야 한다.
  숫자: 글자 값은 보지 않고 숫자만 맞춘다. 묶음 이름("피우지 않음"/"피우지 않는다")은 정답 SQL을 쓴 사람이 임의로 정한 글이라
  프롬프트를 거기에 맞추면 정답셋에 끼워 맞추게 된다. 개발 몫에서 틀린 4건 중 3건이 이름 글자만 달랐다.

사용법 (eval/ 폴더에서, 앱이 떠 있는 상태):
    .venv/bin/python -m stats.run_stats_eval --split dev --label dev-v1
"""
from __future__ import annotations

import argparse
import json
import re
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

import run_record
from run_record import EVAL_DIR

GOLD = Path(__file__).resolve().parent / "stats-gold-v1.jsonl"
MIN_GROUP = 10


def post(api: str, question: str) -> dict:
    request = urllib.request.Request(f"{api}/api/stats/ask", data=json.dumps({"question": question}).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.load(response)


def norm(value):
    if value is None or value == "":
        return None
    if isinstance(value, (int, float)):
        return round(float(value), 1)
    text = str(value).strip()
    try:
        return round(float(text), 1)
    except ValueError:
        return text


def suppress(columns: list[str], rows: list[list]) -> list[list]:
    n = [c.lower() for c in columns].index("n")
    out = []
    for row in rows:
        count = norm(row[n])
        if isinstance(count, float) and count >= MIN_GROUP:
            out.append(row)
        else:
            out.append([None if isinstance(norm(v), float) else v for v in row])
    return out


def contains(pred_row: list, gold_row: list, numeric: bool = False) -> bool:
    keep = (lambda v: isinstance(v, float)) if numeric else (lambda v: True)
    need = Counter(v for v in map(norm, gold_row) if keep(v))
    have = Counter(v for v in map(norm, pred_row) if keep(v))
    return all(have[k] >= c for k, c in need.items())


def matches(gold: dict, pred_table: dict | None, ordered: bool, numeric: bool = False) -> bool:
    if not pred_table:
        return False
    gold_rows = suppress(gold["columns"], gold["rows"])
    pred_rows = pred_table["rows"]
    if len(gold_rows) != len(pred_rows):
        return False
    if ordered:
        return all(contains(p, g, numeric) for p, g in zip(pred_rows, gold_rows))
    remaining = list(pred_rows)
    for g in gold_rows:
        hit = next((i for i, p in enumerate(remaining) if contains(p, g, numeric)), None)
        if hit is None:
            return False
        remaining.pop(hit)
    return True


def summarize(rows: list[dict]) -> dict:
    answer = [r for r in rows if r["expectedBehavior"] == "answer"]
    refuse = [r for r in rows if r["expectedBehavior"] == "refuse"]
    return {
        "n": len(rows),
        "execMatch": f"{sum(r['match'] for r in answer)}/{len(answer)}",
        "execMatchNumeric": f"{sum(r['matchNumeric'] for r in answer)}/{len(answer)}",
        "executedButShouldNot": sum(1 for r in refuse if r["executed"]),
        "refusedCorrectly": f"{sum(1 for r in refuse if not r['executed'])}/{len(refuse)}",
        "answerableRefused": sum(1 for r in answer if r["decision"] in ("NO_DATA", "REFUSE")),
        "failed": sum(1 for r in rows if r["decision"] == "FAILED"),
        "retried": sum(1 for r in rows if r["attempts"] > 1),
        "decisions": dict(Counter(r["decision"] for r in rows)),
        "byCategory": {c: f"{sum(r['match'] for r in answer if r['category'] == c)}/{sum(1 for r in answer if r['category'] == c)}"
                       for c in sorted({r["category"] for r in answer})},
        "avgTotalMs": round(sum(r["totalMs"] for r in rows) / max(len(rows), 1)),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--split", default="dev")
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--label", default="dev")
    args = parser.parse_args()
    git = run_record.git_state()  # 시작할 때 잰다 (실행 중에 파일을 고쳐도 기록이 바뀌지 않게, 2026-11-12 dev-v2 교훈)
    if git["dirty"]:
        print("주의: 커밋하지 않은 변경이 있다. 기록에 dirty=true로 남는다 (비교 수치로 쓰지 않는다)")
    gold = [json.loads(line) for line in GOLD.read_text(encoding="utf-8").splitlines() if line.strip()]
    gold = [g for g in gold if g["split"] == args.split]
    rows = []
    for run in range(1, args.runs + 1):
        for g in gold:
            try:
                result = post(args.api, g["question"])
            except urllib.error.HTTPError as e:
                result = {"decision": f"HTTP{e.code}", "table": None, "sql": None, "errors": [e.read().decode()[:200]],
                          "metrics": {"attempts": 0, "totalMs": 0, "promptVersion": None}, "llmDecision": None}
            table = result.get("table")
            ordered = bool(g.get("goldSql") and re.search(r"order by .* limit", g["goldSql"], re.I))
            row = {"run": run, "id": g["id"], "category": g["category"], "expectedBehavior": g["expectedBehavior"],
                   "question": g["question"], "decision": result["decision"], "llmDecision": result.get("llmDecision"),
                   "sql": result.get("sql"), "errors": result.get("errors"),
                   "executed": table is not None,
                   "match": g["expectedBehavior"] == "answer" and matches(g["expected"], table, ordered),
                   "matchNumeric": g["expectedBehavior"] == "answer" and matches(g["expected"], table, ordered, numeric=True),
                   "suppressedRows": table["suppressedRows"] if table else None,
                   "predRows": table["rows"][:30] if table else None, "predColumns": table["columns"] if table else None,
                   "attempts": result["metrics"]["attempts"], "totalMs": result["metrics"]["totalMs"],
                   "promptVersion": result["metrics"].get("promptVersion")}
            rows.append(row)
            ok = "○" if row["match"] or (g["expectedBehavior"] == "refuse" and not row["executed"]) else "✗"
            print(f"[{run}] {g['id']} {g['category']:<12} → {row['decision']:<8} {ok} | {g['question'][:34]}", flush=True)
    summary = {"all": summarize(rows), **{f"run{n}": summarize([r for r in rows if r["run"] == n]) for n in range(1, args.runs + 1)}}
    print(json.dumps(summary["all"], ensure_ascii=False, indent=1))
    run_id = run_record.new_run_id("stats-sql", args.label)
    out = run_record.save(run_id, "stats-sql", "stats", label=args.label,
                          dataset={"name": GOLD.stem, "split": args.split, "questions": len(gold)},
                          config={"promptVersion": sorted({r["promptVersion"] for r in rows if r["promptVersion"]}),
                                  "runs": args.runs, "minGroupSize": MIN_GROUP},
                          summary=summary,
                          headline={k: summary["all"][k] for k in ("execMatch", "execMatchNumeric", "executedButShouldNot", "failed")},
                          details=rows, git=git)
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
