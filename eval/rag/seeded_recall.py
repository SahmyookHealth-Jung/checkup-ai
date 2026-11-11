"""오류 심은 세트의 채점 결과에서, 채점기가 심은 오류를 잡은 비율을 낸다 (ADR 0010 5절, ADR 0009 "채점기 검증").

채점은 기존 실행기로 한다 (이 스크립트는 모델을 부르지 않는다):
    .venv/bin/python -m rag.run_faithfulness_judge --calibration seeded-v1 --setting off --label seeded
그 결과 파일을 받아 종류별로 맞은 건수와 통과 기준(rag/seeded_errors.py의 CRITERIA) 충족 여부를 낸다.
기준은 채점 전에 고정했다. 결과를 보고 바꾸지 않는다.

사용법 (eval/ 폴더에서):
    .venv/bin/python -m rag.seeded_recall --judge results/faithfulness/<runId>.json [--name seeded-v1]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import run_record
from rag.seeded_errors import score
from run_record import EVAL_DIR


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--judge", type=Path, required=True)
    parser.add_argument("--name", default="seeded-v1")
    args = parser.parse_args()
    judge = json.loads((args.judge if args.judge.is_absolute() else EVAL_DIR / args.judge).read_text(encoding="utf-8"))
    manifest = json.loads((EVAL_DIR / "calibration" / f"{args.name}.manifest.json").read_text(encoding="utf-8"))
    if judge["dataset"].get("name") != args.name:
        raise SystemExit(f"채점 결과의 대상은 {judge['dataset'].get('name')}다. {args.name}를 채점한 파일을 준다")
    if judge["config"]["rubric"] != manifest["rubric"]:
        raise SystemExit("채점 기준 버전이 세트를 만들 때와 다르다. 바탕 문장을 그 기준으로 골랐으므로 같은 기준으로 채점한다")
    rows = [json.loads(line) for line in
            (EVAL_DIR / "calibration" / f"{args.name}.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    report = score(rows, judge["details"])

    for kind, entry in report["byType"].items():
        rate = "-" if entry["rate"] is None else f"{entry['rate']:.2f}"
        missing = f" | 채점 실패 {len(entry['notJudged'])}" if entry["notJudged"] else ""
        wrong = f" | 기대와 다름: {', '.join(entry['wrong'])}" if entry["wrong"] else ""
        print(f"{entry['name']:<28} {entry['correct']:>3}/{entry['judged']:<3} ({rate}){missing}{wrong}")
    print()
    for entry in report["criteria"].values():
        bar = f"{entry['minRate']:.0%} 이상" if "minRate" in entry else f"틀린 것 {entry['maxWrong']}개 이하"
        verdict = {True: "충족", False: "미달", None: "채점 없음"}[entry["met"]]
        print(f"[{verdict}] {entry['name']}: {entry['correct']}/{entry['judged']} (기준 {bar})")

    run_id = run_record.new_run_id("seeded", f"{args.name}_{judge['config']['setting']}")
    out = run_record.save(
        run_id, "seeded", "faithfulness", label=judge["config"]["setting"],
        dataset={"name": args.name, "source": "seeded", "judgeRun": judge["runId"], "sentences": len(rows)},
        config={"rubric": judge["config"]["rubric"], "judgeSetting": judge["config"]["setting"],
                "repeats": judge["config"]["repeats"], "criteria": manifest["criteria"]},
        summary={"byType": report["byType"], "criteria": report["criteria"]},
        headline={f"met.{key}": entry["met"] for key, entry in report["criteria"].items()},
        details=report["sentences"])
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
