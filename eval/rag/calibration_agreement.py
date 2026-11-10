"""채점기와 사람 라벨의 일치도 (ADR 0009 4절). 사람 라벨을 읽는 곳은 이 스크립트뿐이다.

채점기 결과(rag.run_faithfulness_judge --calibration ...)와 사람 라벨(eval/calibration/<이름>.labels.json)을 받아
축마다 κ(3단계, 2단계), 단순 일치율, 혼동 행렬, 불일치 목록을 낸다. 채점기는 반복 회차의 다수결을 쓴다(동률은 엄격한 쪽).

사용법 (eval/ 폴더에서):
    .venv/bin/python -m rag.calibration_agreement --judge results/faithfulness/<runId>.json [--labels calibration/calibration-v1.labels-faithfulness-v1.1.json]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import run_record
from rag.agreement import binary, cohen_kappa, confusion, majority
from rag.faithfulness_rubric import AXES, VERSION as RUBRIC_VERSION
from run_record import EVAL_DIR

AXIS_ORDER = {axis: list(spec["labels"]) for axis, spec in AXES.items()}


def compare(labels: dict, judge: dict) -> dict:
    """labels: calId → 사람 라벨, judge: 채점기 결과 기록. 둘 다 있는 문장만 비교한다"""
    by_id = {d["id"]: d for d in judge["details"]}
    ids = [i for i in labels if i in by_id and all(labels[i].get(a) for a in AXES)]
    report = {"compared": len(ids), "axes": {}, "disagreements": []}
    judged = {}
    for i in ids:
        results = [r["result"] for r in by_id[i]["repeats"] if "result" in r]
        judged[i] = {axis: majority([r[axis] for r in results], axis) for axis in AXES} if results else None
    ids = [i for i in ids if judged[i]]
    for axis in AXES:
        human = [labels[i][axis] for i in ids]
        machine = [judged[i][axis] for i in ids]
        entry = {"agreement": round(sum(1 for h, m in zip(human, machine) if h == m) / len(ids), 3) if ids else None,
                 "kappa": cohen_kappa(human, machine) if ids else None,
                 "confusion": confusion(human, machine, AXIS_ORDER[axis])}
        if axis == "support":
            entry["kappaBinary"] = cohen_kappa(binary(human), binary(machine)) if ids else None
        report["axes"][axis] = entry
    for i in ids:
        diff = [a for a in AXES if labels[i][a] != judged[i][a]]
        if diff:
            last = next(r["result"] for r in by_id[i]["repeats"] if "result" in r)
            report["disagreements"].append({"id": i, "stratum": by_id[i]["stratum"], "axes": diff,
                                            "human": {a: labels[i][a] for a in AXES}, "judge": judged[i],
                                            "memo": labels[i].get("memo") or "", "judgeReason": last["reason"]})
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--judge", type=Path, required=True)
    parser.add_argument("--labels", type=Path, default=Path(f"calibration/calibration-v1.labels-{RUBRIC_VERSION}.json"))
    args = parser.parse_args()
    judge_path = args.judge if args.judge.is_absolute() else EVAL_DIR / args.judge
    labels_path = args.labels if args.labels.is_absolute() else EVAL_DIR / args.labels
    judge = json.loads(judge_path.read_text(encoding="utf-8"))
    labels_file = json.loads(labels_path.read_text(encoding="utf-8"))
    if labels_file["rubricVersion"] != RUBRIC_VERSION or judge["config"]["rubric"] != RUBRIC_VERSION:
        raise SystemExit("채점 기준 버전이 다르다. 같은 버전끼리만 비교한다")
    labels = {row["calId"]: row for row in labels_file["labels"]}
    report = compare(labels, judge)
    for axis, entry in report["axes"].items():
        extra = f", 2단계 κ {entry['kappaBinary']}" if "kappaBinary" in entry else ""
        print(f"{AXES[axis]['name']}: 일치율 {entry['agreement']}, κ {entry['kappa']}{extra}")
    print(f"비교 {report['compared']}문장, 불일치 {len(report['disagreements'])}문장")

    run_id = run_record.new_run_id("agreement", f"{labels_file['set']}_{judge['config']['setting']}")
    out = run_record.save(
        run_id, "agreement", "faithfulness", label=judge["config"]["setting"],
        dataset={"name": labels_file["set"], "source": "calibration", "judgeRun": judge["runId"]},
        config={"rubric": RUBRIC_VERSION, "judgeSetting": judge["config"]["setting"], "repeats": judge["config"]["repeats"]},
        summary={k: v for k, v in report.items() if k != "disagreements"},
        headline={"kappa.support": report["axes"]["support"]["kappa"],
                  "kappaBinary.support": report["axes"]["support"]["kappaBinary"]},
        details=report["disagreements"])
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
