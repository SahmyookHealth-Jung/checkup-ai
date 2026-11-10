"""보정 불일치 분류 확인 화면 (ADR 0009 결과 절, 2차 보정).

사람 라벨과 채점기가 갈린 문장마다 Claude가 원인을 분류했다. 사용자가 그 분류에 동의하는지 확인한다.
κ는 바꾸지 않는다 (눈가림 라벨 그대로). "불일치 원인을 사람이 확인했다"는 근거를 남기는 것이 목적이다.

- 화면: eval/.cache/labeling/<이름>.html (근거 원문이 들어가므로 커밋하지 않는다)
- 결과: 화면에서 내려받거나 브라우저 저장값을 읽어 eval/calibration/<이름>.json으로 둔다

사용법 (eval/ 폴더에서): .venv/bin/python -m rag.build_disagreement_review --judge results/faithfulness/<2차 보정>.json
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from rag.agreement import majority
from rag.build_calibration_set import CHUNKS, SCREEN
from rag.faithfulness_rubric import AXES, VERSION as RUBRIC_VERSION
from run_record import EVAL_DIR

# Claude의 분류 (2026-11-11, 2차 보정 v1.2). 정도 차이만 있는 3개(cal-018, 033, 046)는 확인 대상에서 뺀다
CLASSIFICATION = {
    "human_missed": ("사람이 놓친 근거 없는 내용",
                     "인용한 근거 문단에 이 문장의 내용이 없다고 본다. 맞는 말이라도 근거가 받쳐 주지 않는다."),
    "appropriateness": ("사람이 적절성을 뒷받침에 섞음",
                        "근거에는 문장 내용이 그대로 있다. 메모의 지적은 '더 나은 설명이 필요하다'는 적절성 문제로 본다."),
    "rubric_judge_right": ("기준대로면 채점기가 맞음",
                           "v1.2 판단 요령(생활습관 조언은 근거가 있어야 함, 구간 수치는 인용 근거에 있어야 함)대로라면 채점기 판정이 맞다."),
    "judge_strict": ("채점기가 엄격한 편",
                     "채점기가 작은 표현 차이까지 감점했다. 사람 판정(지지)도 충분히 납득된다."),
    "rubric_gap": ("기준의 빈칸",
                   "문장은 근거와 맞지만, 그 근거(고혈압·당뇨·이상지질혈증용 정의)를 다른 항목에 적용했다. 뒷받침 축은 이 경우를 다루지 않는다."),
}
ASSIGNED = {
    "cal-001": "human_missed", "cal-005": "human_missed", "cal-013": "human_missed", "cal-014": "human_missed",
    "cal-015": "human_missed", "cal-019": "human_missed", "cal-029": "human_missed", "cal-041": "human_missed",
    "cal-004": "appropriateness", "cal-025": "appropriateness", "cal-026": "appropriateness",
    "cal-037": "rubric_judge_right", "cal-040": "rubric_judge_right",
    "cal-030": "judge_strict", "cal-032": "judge_strict",
    "cal-035": "rubric_gap",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--judge", type=Path, required=True)
    parser.add_argument("--name", default="calibration-v1.disagreement-review")
    args = parser.parse_args()
    judge = json.loads((args.judge if args.judge.is_absolute() else EVAL_DIR / args.judge).read_text(encoding="utf-8"))
    if judge["config"]["rubric"] != RUBRIC_VERSION:
        raise SystemExit("채점 기준 버전이 다르다")
    labels = {l["calId"]: l for l in json.loads(
        (EVAL_DIR / "calibration" / f"calibration-v1.labels-{RUBRIC_VERSION}.json").read_text(encoding="utf-8"))["labels"]}
    cal_rows = {r["calId"]: r for r in (json.loads(line) for line in
                (EVAL_DIR / "calibration" / "calibration-v1.jsonl").read_text(encoding="utf-8").splitlines())}
    chunks = {row["id"]: row for row in (json.loads(line) for line in CHUNKS.read_text(encoding="utf-8").splitlines())}
    by_id = {d["id"]: d for d in judge["details"]}
    rows = []
    for cal_id, category in ASSIGNED.items():
        d, r = by_id[cal_id], cal_rows[cal_id]
        results = [x["result"] for x in d["repeats"] if "result" in x]
        rows.append({
            "calId": cal_id, "item": r["item"], "text": r["text"],
            "sources": [{"id": c, "publisher": chunks[c]["metadata"].get("publisher"),
                         "section": chunks[c]["metadata"].get("section"), "text": chunks[c]["text"]} for c in r["sources"]],
            "human": {"support": labels[cal_id]["support"], "memo": labels[cal_id].get("memo") or ""},
            "judge": {"support": majority([x["support"] for x in results], "support"),
                      "repeats": [x["support"] for x in results],
                      "unsupportedClaims": results[0]["unsupportedClaims"], "reason": results[0]["reason"]["support"]},
            "category": category,
        })
    data = {"set": args.name, "judgeRun": judge["runId"], "rubricVersion": RUBRIC_VERSION,
            "supportLabels": {k: v[0] for k, v in AXES["support"]["labels"].items()},
            "categories": {k: {"name": v[0], "desc": v[1]} for k, v in CLASSIFICATION.items()}, "rows": rows}
    html = (Path(__file__).parent / "disagreement_review.html").read_text(encoding="utf-8")
    html = html.replace("__DATA__", json.dumps(data, ensure_ascii=False).replace("</", "<\\/"))
    SCREEN.mkdir(parents=True, exist_ok=True)
    out = SCREEN / f"{args.name}.html"
    out.write_text(html, encoding="utf-8")
    print(f"화면: {out.relative_to(EVAL_DIR)} ({len(rows)}문장)")


if __name__ == "__main__":
    main()
