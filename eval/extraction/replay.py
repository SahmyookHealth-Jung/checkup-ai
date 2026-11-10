"""재생 비교 도우미 (ADR 0004 5절).

평가에서 저장한 LLM 출력(1차 출력, 재요청 입력·출력)에 현재 검증기를 다시 적용해, 검증 규칙만 바꾼 효과를 잰다.
모델을 부르지 않는다. 기준은 같은 LLM 출력으로 실제로 실행한 결과(결과 JSON)다.

사용법 (eval/ 폴더에서):
  1) 재생 입력 만들기 (결과 JSON의 진단 기록에서)
     .venv/bin/python -m extraction.replay inputs results/extraction/<결과>.json \\
         --data ../data/synthetic/extraction/heldout-v2 --out .cache/replay/h2/in
  2) 현재 검증기로 재생 (backend/에서, 경로는 절대 경로)
     ./gradlew :report:replay --args="<eval>/.cache/replay/h2/in <eval>/.cache/replay/h2/out"
  3) 실제 실행과 비교
     .venv/bin/python -m extraction.replay compare results/extraction/<결과>.json \\
         --data ../data/synthetic/extraction/heldout-v2 --replayed .cache/replay/h2/out

한계: 현재 검증기가 찾은 누락 조각이 실제 실행의 재요청 입력과 다르면, 그 보고서에는 새 조각에 대한 모델 답이 없다.
그런 보고서는 "재요청 입력이 달라진 보고서"로 따로 보여 준다. 그 보고서의 재생 결과는 근사치다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from .metrics import Score, score_document

EVAL_DIR = Path(__file__).resolve().parent.parent
CACHE_DIR = EVAL_DIR / ".cache" / "extraction"
HEADER_KEYS = ("examDate", "examType", "sex", "age")


def build_inputs(results: Path, data: Path, out: Path) -> None:
    run = json.loads(results.read_text(encoding="utf-8"))
    summary = run["summary"]
    out.mkdir(parents=True, exist_ok=True)
    written, approximate = 0, []
    for doc in run["documents"]:
        diagnostics = doc.get("diagnostics") or {}
        if doc.get("error") or "firstPassItems" not in diagnostics:
            continue
        pdf = data / f"{doc['id']}.{summary.get('format', 'pdf')}"
        header = doc.get("header") or _header_from_cache(summary, pdf)
        if header is None:
            approximate.append(doc["id"])
        (out / f"{doc['id']}.json").write_text(json.dumps({
            "id": doc["id"], "pdf": str(pdf.resolve()), "header": header,
            "firstPassItems": diagnostics.get("firstPassItems"),
            "retryItems": diagnostics.get("retryItems"),
            "retryInput": diagnostics.get("retryInput"),
        }, ensure_ascii=False), encoding="utf-8")
        written += 1
    print(f"재생 입력 {written}개 → {out}")
    if approximate:
        print(f"수검자 정보(검진일·나이)를 찾지 못한 보고서: {approximate}. 누락 조각 판별이 실제와 다를 수 있다")


def _header_from_cache(summary: dict, pdf: Path) -> dict | None:
    """예전 결과 JSON에는 수검자 정보가 없다. 같은 실행의 응답 캐시에서 찾는다 (run_eval의 캐시 키와 같다)."""
    info = {k: summary[k] for k in ("model", "promptVersion", "pipeline")}
    key_source = json.dumps(info, sort_keys=True) + summary.get("format", "pdf")
    key = hashlib.sha256(key_source.encode() + pdf.read_bytes()).hexdigest()[:32]
    cache_file = CACHE_DIR / f"{key}.json"
    if not cache_file.exists():
        return None
    report = json.loads(cache_file.read_text(encoding="utf-8")).get("report") or {}
    return {k: report.get(k) for k in HEADER_KEYS}


def compare(results: Path, data: Path, replayed: Path) -> None:
    run = json.loads(results.read_text(encoding="utf-8"))
    actual_total, replay_total = Score(), Score()
    changes, changed_input = [], []
    for doc in run["documents"]:
        out_file = replayed / f"{doc['id']}.json"
        if not out_file.exists():
            continue
        expected = json.loads((data / f"{doc['id']}.expected.json").read_text(encoding="utf-8"))["items"]
        out = json.loads(out_file.read_text(encoding="utf-8"))
        replay = score_document(expected, out["items"] or [])
        actual = Score(tp=doc["tp"], fp=doc["fp"], fn=doc["fn"])
        actual_total, replay_total = actual_total.add(actual), replay_total.add(replay)
        before = {_key(e) for e in doc["errors"]}
        after = {_key(e) for e in replay.errors}
        changes += [(doc["id"], "고침", e) for e in sorted(before - after)]
        changes += [(doc["id"], "새 오류", e) for e in sorted(after - before)]
        if out["retryInputChanged"]:
            changed_input.append(doc["id"])

    print(f"실제 실행 ({run['summary']['pipeline']}): {_line(actual_total)}")
    print(f"현재 검증기로 재생      : {_line(replay_total)}")
    print(f"재요청 입력이 달라진 보고서 (재생 결과가 근사치): {changed_input or '없음'}")
    for report_id, kind, (etype, code, exp, pred) in changes:
        print(f"  {report_id} {kind}: {etype} {code} 정답={exp} 예측={pred}")


def _key(error: dict) -> tuple:
    return error["type"], error["code"], str(error["expected"]), str(error["predicted"])


def _line(score: Score) -> str:
    s = score.summary()
    return f"F1 {s['f1']:.3f} (P {s['precision']:.3f} / R {s['recall']:.3f}, TP {s['tp']} / FP {s['fp']} / FN {s['fn']})"


def main() -> None:
    parser = argparse.ArgumentParser(description="재생 비교 도우미")
    sub = parser.add_subparsers(dest="command", required=True)
    p_in = sub.add_parser("inputs", help="결과 JSON의 진단 기록으로 재생 입력을 만든다")
    p_in.add_argument("results", type=Path)
    p_in.add_argument("--data", type=Path, required=True)
    p_in.add_argument("--out", type=Path, required=True)
    p_cmp = sub.add_parser("compare", help="실제 실행과 재생 결과를 비교한다")
    p_cmp.add_argument("results", type=Path)
    p_cmp.add_argument("--data", type=Path, required=True)
    p_cmp.add_argument("--replayed", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "inputs":
        build_inputs(args.results, args.data, args.out)
    else:
        compare(args.results, args.data, args.replayed)


if __name__ == "__main__":
    main()
