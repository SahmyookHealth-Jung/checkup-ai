"""추출 결과를 저장해 고정한다 (ADR 0008 4절 2번).

추출(Haiku)은 실행마다 흔들린다. 해설 개선을 짝비교할 때 추출까지 다시 하면 두 흔들림이 섞인다.
그래서 처음 보는 양식(heldout-v1~v3) 30개를 현재 추출 파이프라인으로 한 번 추출해 저장하고,
해설 평가는 이 파일을 /api/eval/explain에 보내 해설만 돌린다.

- 저장: eval/frozen/<이름>/<데이터셋>/<id>.json (추출 API 응답에서 report, needsReview, metrics만)
  + manifest.json (모델·파이프라인·설정, 커밋, 정답 대비 F1). 합성 데이터라 커밋한다.
- 추출 응답 캐시(eval/.cache/extraction/)를 그대로 쓴다. 같은 조건이면 다시 부르지 않는다.
- 이미 있는 폴더는 덮어쓰지 않는다. 고정한 추출을 바꾸려면 새 이름으로 만든다.

사용법 (eval/ 폴더에서, 앱이 떠 있어야 한다):
    .venv/bin/python -m rag.freeze_extractions [--name heldout30-v2.5] [--datasets heldout-v1,heldout-v2,heldout-v3]
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

from extraction.metrics import score_document
from extraction.run_eval import _all_ids, _extract, _get_json
from run_record import EVAL_DIR, ROOT, git_state

FROZEN = EVAL_DIR / "frozen"
DATA = ROOT / "data" / "synthetic" / "extraction"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--name", default="heldout30-v2.5")
    parser.add_argument("--datasets", default="heldout-v1,heldout-v2,heldout-v3")
    args = parser.parse_args()
    out_dir = FROZEN / args.name
    if out_dir.exists():
        raise SystemExit(f"이미 있다: {out_dir.relative_to(EVAL_DIR)}. 고정한 추출은 덮어쓰지 않는다")

    info = _get_json(f"{args.api}/api/reports/extract/info")
    call = SimpleNamespace(api=args.api, format="pdf", timeout=600)
    documents, written = [], []
    for dataset in args.datasets.split(","):
        data = DATA / dataset
        for report_id in _all_ids(data):
            expected = json.loads((data / f"{report_id}.expected.json").read_text(encoding="utf-8"))
            response, cached = _extract(call, info, report_id, (data / f"{report_id}.pdf").read_bytes())
            if "error" in response:
                raise SystemExit(f"{dataset}/{report_id} 추출 실패: {response['error'][:200]}. 아무것도 저장하지 않았다")
            score = score_document(expected["items"], response["report"].get("items") or [])
            frozen = {"report": response["report"], "needsReview": response.get("needsReview") or [],
                      "metrics": response.get("metrics")}
            written.append((dataset, report_id, frozen))
            documents.append({"dataset": dataset, "id": report_id, "cached": cached, "f1": round(score.f1, 4),
                              "fp": score.fp, "fn": score.fn, "needsReview": len(frozen["needsReview"])})
            print(f"{dataset}/{report_id}: F1 {score.f1:.3f} (FP {score.fp} / FN {score.fn})"
                  f" | 확인 필요 {len(frozen['needsReview'])} | {'캐시' if cached else '새로 추출'}", flush=True)

    # 모두 성공했을 때만 쓴다 (일부만 고정된 폴더가 남지 않게)
    for dataset, report_id, frozen in written:
        path = out_dir / dataset / f"{report_id}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(frozen, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {"name": args.name, "createdAt": datetime.now().isoformat(timespec="seconds"), "git": git_state(),
                "extraction": info, "datasets": args.datasets.split(","), "reports": len(documents),
                "meanF1": round(sum(d["f1"] for d in documents) / len(documents), 4), "documents": documents}
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"저장: {out_dir.relative_to(EVAL_DIR)} ({len(documents)}개, 평균 F1 {manifest['meanF1']})")


if __name__ == "__main__":
    main()
