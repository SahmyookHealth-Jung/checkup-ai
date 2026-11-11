"""표기 변형 평가 (ADR 0010 4절). 판정 코드가 실제 결과지의 값·단위 표기를 어떻게 처리하는지 잰다.

고정한 추출(eval/frozen/<이름>/)을 /api/eval/explain에 보내 판정 결과를 받고, 판정 대상 항목마다
맞게 판정 / 확인 필요로 알림 / 틀린 판정 / 조용히 지나감 으로 나눈다 (지표 정의는 notation_metrics.py).
정답은 합성 데이터 manifest의 variants(변형 전의 깨끗한 값)에서 온다 (eval/synth/variants.py).

판정은 결정적이라 1회만 돌린다. 해설 문장은 보지 않는다 (판정 해설은 글 근거가 있는 항목만 LLM을 쓴다. 보고서당 Haiku 0~1회).

사용법 (eval/ 폴더에서, 앱이 떠 있어야 한다):
    .venv/bin/python -m rag.freeze_extractions --name heldout-v4-v2.5 --datasets heldout-v4
    .venv/bin/python -m rag.run_notation_eval --frozen heldout-v4-v2.5 --label baseline
"""
from __future__ import annotations

import argparse
import json

import run_record
from rag.notation_metrics import OUTCOMES, assess_report, summarize
from rag.run_explanation_eval import CRITERIA, DATA, FROZEN, _get, _post
from run_record import EVAL_DIR


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--frozen", required=True, help="eval/frozen/ 아래 고정한 추출 이름")
    parser.add_argument("--label", default="baseline")
    args = parser.parse_args()
    criteria = json.loads(CRITERIA.read_text(encoding="utf-8"))
    git = run_record.git_state()
    if git["dirty"]:
        print("주의: 커밋하지 않은 변경이 있다. 기록에 dirty=true로 남는다 (비교 수치로 쓰지 않는다)")
    frozen_dir = FROZEN / args.frozen
    manifest = json.loads((frozen_dir / "manifest.json").read_text(encoding="utf-8"))

    rows = []
    for doc in manifest["documents"]:
        dataset, report_id = doc["dataset"], doc["id"]
        variants = next(json.loads(line) for line in (DATA / dataset / "manifest.jsonl").read_text(encoding="utf-8").splitlines()
                        if json.loads(line)["id"] == report_id).get("variants") or []
        expected = json.loads((DATA / dataset / f"{report_id}.expected.json").read_text(encoding="utf-8"))
        extraction = json.loads((frozen_dir / dataset / f"{report_id}.json").read_text(encoding="utf-8"))
        explanation = _post(f"{args.api}/api/eval/explain", extraction)
        report_rows = [{"dataset": dataset, "id": report_id, **r} for r in assess_report(criteria, expected, variants, explanation)]
        rows += report_rows
        bad = [f"{r['code']}({r['printed']})={r['outcome']}" for r in report_rows if r["outcome"] not in ("correct", "flagged")]
        print(f"{dataset}/{report_id}: 판정 대상 {len(report_rows)}" + (f" | {', '.join(bad)}" if bad else ""), flush=True)

    summary = summarize(rows)
    print(f"\n{'종류':<16}" + "".join(f"{o:>18}" for o in OUTCOMES))
    for kind, counts in summary["byKind"].items():
        print(f"{kind:<16}" + "".join(f"{counts[o]:>18}" for o in OUTCOMES))
    print(f"{'전체':<16}" + "".join(f"{summary[o]:>18}" for o in OUTCOMES))
    print(f"정답이 질환의심인 항목 중 사용자에게 알린 것: {summary['suspectReached']}")

    run_id = run_record.new_run_id("notation", f"{args.frozen}_{args.label}")
    out = run_record.save(
        run_id, "notation", "rag", label=args.label,
        dataset={"name": args.frozen, "source": "frozen", "reports": len(manifest["documents"]),
                 "extraction": {k: manifest["extraction"].get(k) for k in ("model", "promptVersion", "pipeline")}},
        config=_get(f"{args.api}/api/eval/explain/info"), summary=summary,
        headline={o: summary[o] for o in OUTCOMES} | {"suspectReached": summary["suspectReached"]}, details=rows, git=git)
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
