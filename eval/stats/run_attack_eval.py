"""기능 2 공격 문항 평가 (ADR 0013 보완, Codex 적대적 리뷰 후). 문항마다 Haiku 1~2회.

LLM을 속여 숨김을 피하는 SQL을 쓰게 하는 질문이다. 문항마다 결과를 "거절 / 검사가 막음 / 실행됨(숨김 확인)"으로 나누고,
실행된 경우 숨기지 않은 행의 값이 서버가 센 인원 10명 이상인 묶음의 것인지는 앱이 보장한다(ResultTable). 여기서는 실행된
결과를 사람이 읽을 수 있게 남기고, 숨김 없이 나간 작은 값이 있는지를 정답 인원(직접 계산)으로 확인한다.

사용법 (eval/ 폴더에서, 앱이 떠 있는 상태):
    .venv/bin/python -m stats.run_attack_eval --label attack-v2
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import run_record
from run_record import EVAL_DIR
from stats.run_stats_eval import post

ATTACKS = Path(__file__).resolve().parent / "stats-attack-v1.jsonl"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--label", default="attack")
    args = parser.parse_args()
    git = run_record.git_state()
    items = [json.loads(line) for line in ATTACKS.read_text(encoding="utf-8").splitlines() if line.strip()]
    rows = []
    for item in items:
        r = post(args.api, item["question"])
        table = r.get("table")
        outcome = ("거절" if r["decision"] in ("NO_DATA", "REFUSE") else "검사가 막음" if table is None else "실행됨")
        row = {"id": item["id"], "attack": item["attack"], "question": item["question"], "decision": r["decision"],
               "outcome": outcome, "sql": r.get("sql"), "errors": r.get("errors"),
               "columns": table["columns"] if table else None, "rows": table["rows"][:20] if table else None,
               "suppressedRows": table["suppressedRows"] if table else None}
        rows.append(row)
        print(f"{item['id']} {item['attack']:<22} → {outcome} ({r['decision']}) 숨김 {row['suppressedRows']}\n   SQL: {row['sql']}\n   오류: {row['errors']}\n   결과: {row['rows'][:4] if row['rows'] else None}")
    summary = {"n": len(rows), "outcomes": {o: sum(1 for r in rows if r["outcome"] == o) for o in ("거절", "검사가 막음", "실행됨")}}
    print(json.dumps(summary, ensure_ascii=False))
    out = run_record.save(run_record.new_run_id("stats-attack", args.label), "stats-attack", "stats", label=args.label,
                          dataset={"name": ATTACKS.stem}, config={}, summary=summary, headline=summary["outcomes"],
                          details=rows, git=git)
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
