"""고정한 추출에 현재 검증기를 다시 적용해 새 고정 추출을 만든다 (ADR 0004 5절 재생 비교 + ADR 0008 4절 2번).

검증 규칙만 바꿨을 때, 추출을 다시 부르면 LLM 출력 흔들림이 규칙의 효과와 섞인다.
그래서 고정 추출을 만들 때 쓴 LLM 출력(응답 캐시의 1차 출력·재요청 출력)을 그대로 두고 검증만 다시 한다.
모델을 부르지 않는다.

순서 (eval/ 폴더에서):
  1) 재생 입력 만들기
     .venv/bin/python -m rag.freeze_replayed inputs --base heldout-v4-v2.5 --out .cache/replay/v4-v2.6/in
  2) 현재 검증기로 재생 (backend/에서, 경로는 절대 경로)
     ./gradlew :report:replay --args="<eval>/.cache/replay/v4-v2.6/in <eval>/.cache/replay/v4-v2.6/out"
  3) 새 고정 추출 쓰기 (파이프라인 이름은 앱의 /api/reports/extract/info가 아니라 인자로 받는다)
     .venv/bin/python -m rag.freeze_replayed write --base heldout-v4-v2.5 --replayed .cache/replay/v4-v2.6/out \\
         --name heldout-v4-v2.6 --pipeline validated-v2.6-noretry

manifest에 replayOf(바탕 고정 추출)와 문서마다 바탕 대비 F1을 남긴다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from datetime import datetime
from pathlib import Path

from extraction.metrics import score_document
from run_record import EVAL_DIR, ROOT, git_state

FROZEN = EVAL_DIR / "frozen"
DATA = ROOT / "data" / "synthetic" / "extraction"
CACHE_DIR = EVAL_DIR / ".cache" / "extraction"
HEADER_KEYS = ("examDate", "examType", "sex", "age")
SEP = "__"  # 재생 입력 파일 이름: <데이터셋>__<id>.json (데이터셋이 여럿이어도 한 폴더로 재생한다)
# 검증기가 아니라 추출기가 남기는 문제. 재생에는 없으므로 바탕 기록의 건수를 그대로 둔다
EXTRACTOR_ISSUES = ("TRUNCATED_OUTPUT", "INCOMPLETE_FIRST_PASS")


def _cache_key_info(manifest: dict) -> dict:
    """응답 캐시 키에 쓴 추출 정보. 재생본의 extraction.pipeline은 새 이름이라 캐시 키가 아니다 (재생본을 바탕으로 다시 재생할 때)"""
    return manifest.get("cacheKeyInfo") or manifest["extraction"]


def _cached_response(info: dict, pdf: Path) -> dict:
    """run_eval._extract와 같은 캐시 키로 고정 추출 때의 응답(진단 기록 포함)을 찾는다."""
    key_source = json.dumps(info, sort_keys=True) + "pdf"
    key = hashlib.sha256(key_source.encode() + pdf.read_bytes()).hexdigest()[:32]
    cache_file = CACHE_DIR / f"{key}.json"
    if not cache_file.exists():
        raise SystemExit(f"응답 캐시가 없다: {pdf.name} ({cache_file.name}). 재생할 LLM 출력이 없다")
    response = json.loads(cache_file.read_text(encoding="utf-8"))
    if "firstPassItems" not in (response.get("diagnostics") or {}):
        raise SystemExit(f"진단 기록이 없는 캐시: {pdf.name}. 재생할 1차 출력이 없다")
    return response


def build_inputs(base: str, out: Path) -> None:
    manifest = json.loads((FROZEN / base / "manifest.json").read_text(encoding="utf-8"))
    out.mkdir(parents=True, exist_ok=True)
    for doc in manifest["documents"]:
        pdf = DATA / doc["dataset"] / f"{doc['id']}.pdf"
        response = _cached_response(_cache_key_info(manifest), pdf)
        diagnostics = response["diagnostics"]
        report = response["report"]
        name = f"{doc['dataset']}{SEP}{doc['id']}"
        (out / f"{name}.json").write_text(json.dumps({
            "id": name, "pdf": str(pdf.resolve()), "header": {k: report.get(k) for k in HEADER_KEYS},
            "firstPassItems": diagnostics.get("firstPassItems"),
            "retryItems": diagnostics.get("retryItems"),
            "retryInput": diagnostics.get("retryInput"),
        }, ensure_ascii=False), encoding="utf-8")
    print(f"재생 입력 {len(manifest['documents'])}개 → {out}")


def write(base: str, replayed: Path, name: str, pipeline: str) -> None:
    out_dir = FROZEN / name
    if out_dir.exists():
        raise SystemExit(f"이미 있다: {out_dir.relative_to(EVAL_DIR)}. 고정한 추출은 덮어쓰지 않는다")
    manifest = json.loads((FROZEN / base / "manifest.json").read_text(encoding="utf-8"))
    documents, written = [], []
    for doc in manifest["documents"]:
        dataset, report_id = doc["dataset"], doc["id"]
        out_file = replayed / f"{dataset}{SEP}{report_id}.json"
        if not out_file.exists():
            raise SystemExit(f"재생 결과가 없다: {out_file}. 아무것도 저장하지 않았다")
        replay = json.loads(out_file.read_text(encoding="utf-8"))
        base_frozen = json.loads((FROZEN / base / dataset / f"{report_id}.json").read_text(encoding="utf-8"))
        base_metrics = base_frozen.get("metrics") or {}
        base_retried = bool(base_metrics.get("retriedSegments"))
        # 재요청을 껐어도(noretry) 잘림·일찍 멈춤이면 재요청한다. 재요청이 있었는데 조각이 달라졌거나,
        # 새 검증기에서 처음으로 일찍 멈춤 조건이 되면 저장된 LLM 출력으로는 재현할 수 없다.
        # 재요청이 없었고 지금도 없으면, 진단 기록의 누락 의심 조각만 달라진 것이라 재생 결과는 정확하다
        if base_retried and replay.get("retryInputChanged"):
            raise SystemExit(f"{dataset}/{report_id}: 재요청 입력이 달라져 재생 결과가 근사치다. 새로 추출해야 한다")
        if not base_retried and replay.get("firstPassIncomplete"):
            raise SystemExit(f"{dataset}/{report_id}: 새 검증기에서는 재요청할 보고서다. 새로 추출해야 한다")
        expected = json.loads((DATA / dataset / f"{report_id}.expected.json").read_text(encoding="utf-8"))["items"]
        score = score_document(expected, replay["items"] or [])
        # 지연·토큰은 같은 LLM 호출이라 그대로 두고, 문제 건수는 재생한 검증으로 다시 센다
        metrics = dict(base_metrics)
        metrics["pipeline"] = pipeline
        issue_counts = Counter(issue["type"] for issue in replay.get("issues") or [])
        for kind in EXTRACTOR_ISSUES:
            if (base_metrics.get("issues") or {}).get(kind):
                issue_counts[kind] = base_metrics["issues"][kind]
        metrics["issues"] = dict(sorted(issue_counts.items()))
        frozen = {"report": {**base_frozen["report"], "items": replay["items"] or []},
                  "needsReview": replay.get("needsReview") or [], "metrics": metrics}
        written.append((dataset, report_id, frozen))
        documents.append({"dataset": dataset, "id": report_id, "f1": round(score.f1, 4), "fp": score.fp,
                          "fn": score.fn, "needsReview": len(frozen["needsReview"]), "baseF1": doc["f1"]})
        mark = "" if documents[-1]["f1"] == doc["f1"] else f"  (바탕 {doc['f1']:.3f})"
        print(f"{dataset}/{report_id}: F1 {score.f1:.3f}{mark} | 확인 필요 {len(frozen['needsReview'])}"
              f" (바탕 {doc['needsReview']})")

    for dataset, report_id, frozen in written:
        path = out_dir / dataset / f"{report_id}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(frozen, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    extraction = {**manifest["extraction"], "pipeline": pipeline}
    new_manifest = {"name": name, "createdAt": datetime.now().isoformat(timespec="seconds"), "git": git_state(),
                    "extraction": extraction, "cacheKeyInfo": _cache_key_info(manifest), "replayOf": base,
                    "datasets": manifest["datasets"],
                    "reports": len(documents),
                    "meanF1": round(sum(d["f1"] for d in documents) / len(documents), 4),
                    "baseMeanF1": manifest["meanF1"], "documents": documents}
    (out_dir / "manifest.json").write_text(json.dumps(new_manifest, ensure_ascii=False, indent=2) + "\n",
                                           encoding="utf-8")
    print(f"저장: {out_dir.relative_to(EVAL_DIR)} ({len(documents)}개, 평균 F1 {new_manifest['meanF1']}"
          f", 바탕 {manifest['meanF1']})")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    p_in = sub.add_parser("inputs")
    p_in.add_argument("--base", required=True)
    p_in.add_argument("--out", required=True, type=Path)
    p_out = sub.add_parser("write")
    p_out.add_argument("--base", required=True)
    p_out.add_argument("--replayed", required=True, type=Path)
    p_out.add_argument("--name", required=True)
    p_out.add_argument("--pipeline", required=True)
    args = parser.parse_args()
    if args.command == "inputs":
        build_inputs(args.base, args.out)
    else:
        write(args.base, args.replayed, args.name, args.pipeline)


if __name__ == "__main__":
    main()
