"""해설 평가 (ADR 0007 9절, ADR 0008 3절의 지표 묶음). 지표 정의는 explanation_metrics.py에 있다.

입력은 두 가지다.
- 고정한 추출 (기본, ADR 0008 4절 2번): eval/frozen/<이름>/의 추출 결과를 /api/eval/explain에 보낸다.
  해설만 돌린다 (보고서당 Haiku 1회). 조건을 바꿔 짝비교할 때 추출의 흔들림이 섞이지 않는다.
- 실시간 (--live): 데모 API로 추출부터 다시 한다 (보고서당 Haiku 2회). 4단계 수치(개발용 v1 10개)와 같은 방식이다.

Haiku 출력은 실행마다 흔들리므로 같은 조건을 여러 번 돌려 범위로 본다 (기본 2회).
결과는 eval/results/rag/<runId>.json에 공통 형식으로 남기고 eval/results/index.jsonl에 한 줄을 더한다 (run_record.py).

사용법 (eval/ 폴더에서, 앱이 떠 있어야 한다):
    .venv/bin/python -m rag.run_explanation_eval --label baseline                 # heldout 30개 x 2회
    .venv/bin/python -m rag.run_explanation_eval --frozen heldout30-v2.5 --runs 1 --ids heldout-v1/lab-001
    .venv/bin/python -m rag.run_explanation_eval --live --runs 3 --label v1-live   # 개발용 v1 10개

5단계에는 조건(--item-filter, --code-templates)을 요청마다 바꿔 짝비교했다. 2026-11-11 판정 해설을 조회로 확정하며
없앴다 (ADR 0010 1절). 기록의 config.evidence(조회 방식), chunksSha256(청크), evidenceLookupSha256(조회표)으로
어떤 근거로 쟀는지 구분한다.
"""
from __future__ import annotations

import argparse
import json
import urllib.request
from pathlib import Path

import run_record
from rag.explanation_metrics import assess_report, compact_output, summarize
from run_record import EVAL_DIR, ROOT

DATA = ROOT / "data" / "synthetic" / "extraction"
FROZEN = EVAL_DIR / "frozen"
CRITERIA = ROOT / "backend" / "report" / "src" / "main" / "resources" / "criteria" / "general-screening-2026-6.json"
LIVE_IDS = "std-001,std-002,std-003,std-004,sum-001,sum-002,sum-003,nar-001,nar-002,nar-003"
HEADLINE_RANGES = (("endToEnd", "careAdviceRecall"), ("explanationLlm", "sentencePassRate"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--frozen", default="heldout30-v2.5", help="eval/frozen/ 아래 고정한 추출 이름")
    parser.add_argument("--live", action="store_true", help="고정한 추출 대신 데모 API로 추출부터 (개발용 v1)")
    parser.add_argument("--ids", help="쉼표로 구분. 고정 모드는 '데이터셋/id', 실시간 모드는 'id'")
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--label", default="baseline", help="실행 이름 (예: baseline, item-filter)")
    args = parser.parse_args()
    criteria = json.loads(CRITERIA.read_text(encoding="utf-8"))
    git = run_record.git_state()
    if git["dirty"]:
        print("주의: 커밋하지 않은 변경이 있다. 기록에 dirty=true로 남는다 (비교 수치로 쓰지 않는다)")

    if args.live:
        keys = (args.ids or LIVE_IDS).split(",")
        dataset = {"name": "v1-dev10" if not args.ids else "v1-ids", "source": "live", "reports": len(keys)}
    else:
        frozen_dir = FROZEN / args.frozen
        manifest = json.loads((frozen_dir / "manifest.json").read_text(encoding="utf-8"))
        keys = args.ids.split(",") if args.ids else [f"{d['dataset']}/{d['id']}" for d in manifest["documents"]]
        dataset = {"name": args.frozen if not args.ids else f"{args.frozen}-ids", "source": "frozen", "reports": len(keys),
                   "extraction": {k: manifest["extraction"].get(k) for k in ("model", "promptVersion", "pipeline")}
                   | {"frozenAt": manifest["createdAt"], "commit": manifest["git"]["commit"], "meanF1": manifest["meanF1"]}}
    info = _get(f"{args.api}/api/eval/explain/info")
    config = {"model": _get(f"{args.api}/api/reports/extract/info").get("model"), **info,
              "runs": args.runs}
    explain_url = f"{args.api}/api/eval/explain"

    summaries, details = [], []
    for run in range(1, args.runs + 1):
        rows = []
        for key in keys:
            if args.live:
                dataset_name, report_id = "v1", key
                response = _post(f"{args.api}/api/demo/samples/{report_id}/explain", None)
                extraction, explanation = response["extraction"], response["explanation"]
            else:
                dataset_name, report_id = key.split("/")
                extraction = json.loads((frozen_dir / dataset_name / f"{report_id}.json").read_text(encoding="utf-8"))
                explanation = _post(explain_url, extraction)
            expected = json.loads((DATA / dataset_name / f"{report_id}.expected.json").read_text(encoding="utf-8"))
            row = assess_report(criteria, expected, extraction["report"], extraction.get("needsReview") or [], explanation)
            rows.append(row)
            details.append({"run": run, "dataset": dataset_name, "id": report_id, **row,
                            "output": compact_output(explanation)})
            print(f"[{run}] {key}: 대상 {row['targets']} | 문장 {row['sentencesKept']}/{row['sentencesGenerated']} | "
                  f"권고 {len(row['expectedSuspect']) - len(row['misses'])}/{len(row['expectedSuspect'])}"
                  + (" (놓침: " + ", ".join(f"{m['code']}={m['cause']}" for m in row["misses"]) + ")" if row["misses"] else "")
                  + f" | 근거 없음 {row['noEvidenceItems']} | 금지 {row['forbiddenHits']} | {row['totalMs'] / 1000:.1f}초"
                  + (f" | 실패 {row['llmError']}" if row["llmError"] else ""), flush=True)
        summary = {"run": run, **summarize(rows)}
        summaries.append(summary)
        print(json.dumps(summary, ensure_ascii=False))

    ranges = {f"{group}.{key}": [min(s[group][key] for s in summaries), max(s[group][key] for s in summaries)]
              for group, key in HEADLINE_RANGES}
    headline = ranges | {"forbiddenHits": sum(s["explanationLlm"]["forbiddenHits"] for s in summaries),
                         "llmErrors": sum(s["explanationLlm"]["llmErrors"] for s in summaries)}
    run_id = run_record.new_run_id("explanation", f"{dataset['name']}_{args.label}")
    out = run_record.save(run_id, "explanation", "rag", label=args.label, dataset=dataset, config=config,
                          summary={"runs": summaries, "ranges": ranges}, headline=headline, details=details, git=git)
    print(f"범위 ({args.runs}회): {json.dumps(ranges, ensure_ascii=False)}")
    print(f"저장: {out.relative_to(EVAL_DIR)}")


def _get(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)


def _post(url: str, body: dict | None) -> dict:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.load(response)


if __name__ == "__main__":
    main()
