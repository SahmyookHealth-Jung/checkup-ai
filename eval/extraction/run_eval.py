"""추출 평가 실행: 합성 결과지를 추출 API에 보내고 정답과 비교한다.

사용법 (eval/ 폴더에서, 앱이 떠 있어야 한다):
    .venv/bin/python -m extraction.run_eval --subset smoke    # 6개, 로컬 CPU 약 1시간
    .venv/bin/python -m extraction.run_eval --subset full     # 30개, 로컬 CPU 약 2~3시간
    .venv/bin/python -m extraction.run_eval --ids sum-008     # 지정한 보고서만
    .venv/bin/python -m extraction.run_eval --subset full --data ../data/synthetic/extraction/heldout-v1

- 응답은 eval/.cache/extraction/에 캐시한다.
  - 캐시 키는 (모델, 프롬프트 버전, 파이프라인, 입력 형식, 파일 내용)이다. 같은 조건이면 다시 호출하지 않는다.
  - 오류 응답은 캐시하지 않는다.
- 결과는 eval/results/extraction/에 JSON으로 저장하고, history.md에 한 줄을 추가한다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime
from pathlib import Path

from .metrics import Score, score_document, score_failed_document, score_fields

EVAL_DIR = Path(__file__).resolve().parent.parent
CACHE_DIR = EVAL_DIR / ".cache" / "extraction"
RESULTS_DIR = EVAL_DIR / "results" / "extraction"
DEFAULT_DATA = EVAL_DIR.parent / "data" / "synthetic" / "extraction" / "v1"

# 템플릿별 2개씩. 로컬 CPU에서 반복 개선할 때 쓰는 작은 평가셋
SMOKE_IDS = ["std-001", "std-002", "sum-001", "sum-002", "nar-001", "nar-002"]


def main() -> None:
    parser = argparse.ArgumentParser(description="결과지 추출 평가")
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--subset", choices=["smoke", "full"], default="smoke")
    parser.add_argument("--ids", help="쉼표로 구분한 보고서 id. 주면 --subset 대신 쓴다")
    parser.add_argument("--format", choices=["pdf", "txt"], default="pdf", help="API에 보낼 입력 형식")
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=1800, help="보고서 하나당 최대 대기 초")
    args = parser.parse_args()

    info = _get_json(f"{args.api}/api/reports/extract/info")
    if args.ids:
        ids, args.subset = [i.strip() for i in args.ids.split(",") if i.strip()], "ids"
    else:
        ids = SMOKE_IDS if args.subset == "smoke" else _all_ids(args.data)
    print(f"평가 시작: {info} | {args.data.name} {args.subset} {len(ids)}개 | 입력 {args.format}", flush=True)

    # first_total: 같은 실행의 1차 추출 원본(검증 전) 점수. 최종 점수와 짝지어 비교하면
    # 모델 출력이 실행마다 흔들려도 "검증이 바꾼 것"만 정확히 잴 수 있다 (v1 평가에서 배운 점).
    total, first_total, per_template, documents = Score(), Score(), {}, []
    for index, report_id in enumerate(ids, start=1):
        expected = json.loads((args.data / f"{report_id}.expected.json").read_text(encoding="utf-8"))
        content = (args.data / f"{report_id}.{args.format}").read_bytes()
        print(f"[{index}/{len(ids)}] {datetime.now():%H:%M:%S} {report_id} 추출 중...", flush=True)
        response, cached = _extract(args, info, report_id, content)

        diagnostics = response.get("diagnostics") or {}
        if "error" in response:
            score = score_failed_document(expected["items"], response["error"])
            first = score
        else:
            score = score_document(expected["items"], response["report"].get("items") or [])
            first = score_document(expected["items"], diagnostics.get("firstPassItems") or []) \
                if "firstPassItems" in diagnostics else score

        template = report_id.split("-")[0]
        total = total.add(score)
        first_total = first_total.add(first)
        per_template[template] = per_template.get(template, Score()).add(score)
        metrics = response.get("metrics") or {}
        documents.append({"id": report_id, "cached": cached, **score.summary(), "firstPass": first.summary(),
                          "metrics": metrics, "error": response.get("error"), "errors": score.errors,
                          "diagnostics": diagnostics or None})
        print(f"      → 1차 F1={first.f1:.3f} → 최종 F1={score.f1:.3f} (TP {score.tp} / FP {score.fp} / FN {score.fn})"
              f" | {'캐시' if cached else str(round((metrics.get('latencyMs') or 0) / 1000)) + '초'}"
              f" | 호출 {metrics.get('attempts', '-')}회, 재요청 조각 {metrics.get('retriedSegments', 0)}개"
              + (f" | 오류: {response['error'][:80]}" if "error" in response else ""), flush=True)

    _save(args, info, ids, total, first_total, per_template, documents)


def _extract(args, info: dict, report_id: str, content: bytes) -> tuple[dict, bool]:
    key_source = json.dumps(info, sort_keys=True) + args.format
    key = hashlib.sha256(key_source.encode() + content).hexdigest()[:32]
    cache_file = CACHE_DIR / f"{key}.json"
    if cache_file.exists():
        return json.loads(cache_file.read_text(encoding="utf-8")), True

    started = time.monotonic()
    url = f"{args.api}/api/reports/extract?diagnostics=true"
    try:
        if args.format == "txt":
            response = _post(url, content, "text/plain;charset=UTF-8", args.timeout)
        else:
            body, content_type = _multipart(f"{report_id}.pdf", content)
            response = _post(url, body, content_type, args.timeout)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        detail = e.read().decode("utf-8", "replace")[:500] if isinstance(e, urllib.error.HTTPError) else ""
        return {"error": f"{type(e).__name__}: {e} {detail}".strip(),
                "elapsedMs": int((time.monotonic() - started) * 1000)}, False

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_file.write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
    return response, False


def _save(args, info: dict, ids: list[str], total: Score, first_total: Score, per_template: dict,
          documents: list[dict]) -> None:
    calls = [d["metrics"] for d in documents if d["metrics"]]
    avg = lambda key: round(sum(c.get(key) or 0 for c in calls) / len(calls)) if calls else None
    summary = {
        "runAt": datetime.now().isoformat(timespec="seconds"),
        **info,
        "dataset": args.data.name,
        "subset": args.subset,
        "format": args.format,
        "documents": len(ids),
        "failed": sum(1 for d in documents if d["error"]),
        "itemF1": total.summary(),
        "firstPassF1": first_total.summary(),  # 같은 실행의 1차 추출(검증 전) 점수
        "byTemplate": {t: s.summary() for t, s in sorted(per_template.items())},
        # 보조 지표: 값이 맞은 항목의 단위·참고치·판정 정확도 (metrics.py 참고)
        "fieldAccuracy": score_fields(total.matches),
        "firstPassFieldAccuracy": score_fields(first_total.matches),
        "fieldAccuracyByTemplate": {t: {k: v["accuracy"] for k, v in score_fields(s.matches).items()}
                                    for t, s in sorted(per_template.items())},
        "avgLatencyMs": avg("latencyMs"),
        "avgPromptTokens": avg("promptTokens"),
        "avgCompletionTokens": avg("completionTokens"),
        # validated 파이프라인: LLM 호출 수, 재요청한 조각 수, 검증에서 찾은 문제 유형별 건수
        "totalAttempts": sum(c.get("attempts") or 1 for c in calls),
        "totalRetriedSegments": sum(c.get("retriedSegments") or c.get("retriedLines") or 0 for c in calls),
        "issueCounts": _sum_counts(c.get("issues") or {} for c in calls),
    }

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    model = str(info.get("model", "unknown")).replace(":", "-").replace("/", "-")
    dataset = "" if args.data.name == DEFAULT_DATA.name else f"{args.data.name}_"
    out = RESULTS_DIR / f"{stamp}_{info.get('pipeline')}_{model}_{dataset}{args.subset}_{args.format}.json"
    out.write_text(json.dumps({"summary": summary, "documents": documents}, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")

    history = RESULTS_DIR / "history.md"
    if not history.exists():
        history.write_text("# 추출 평가 기록\n\n대표 지표: 항목 F1 (code + value 일치). 정밀도 P, 재현율 R.\n"
                           "1차 F1: 같은 실행의 1차 추출(검증 전) 점수. 최종 F1과의 차이가 검증의 효과다.\n\n"
                           "| 실행 시각 | 파이프라인 | 모델 | 프롬프트 | 평가셋 | 입력 | F1 | P | R | 실패 | 평균 지연 "
                           "| 평균 토큰(입/출) | 1차 F1 | 필드 정확도 (단위/참고치/판정) |\n"
                           "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n", encoding="utf-8")
    f1 = summary["itemF1"]
    latency = f"{summary['avgLatencyMs'] / 1000:.0f}초" if summary["avgLatencyMs"] else "-"
    fields = "/".join(f"{v['accuracy'] * 100:.1f}" if v["accuracy"] is not None else "-"
                      for v in summary["fieldAccuracy"].values())
    subset = f"{args.subset}({len(ids)})" if not dataset else f"{args.data.name} {args.subset}({len(ids)})"
    with history.open("a", encoding="utf-8") as f:
        f.write(f"| {summary['runAt']} | {info.get('pipeline')} | {info.get('model')} | {info.get('promptVersion')} "
                f"| {subset} | {args.format} | **{f1['f1']:.3f}** | {f1['precision']:.3f} "
                f"| {f1['recall']:.3f} | {summary['failed']} | {latency} "
                f"| {summary['avgPromptTokens']}/{summary['avgCompletionTokens']} "
                f"| {summary['firstPassF1']['f1']:.3f} | {fields} |\n")

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"저장: {out.relative_to(EVAL_DIR)}")


def _sum_counts(counts) -> dict:
    total: dict[str, int] = {}
    for count in counts:
        for key, value in count.items():
            total[key] = total.get(key, 0) + value
    return dict(sorted(total.items()))


def _all_ids(data: Path) -> list[str]:
    rows = (json.loads(line) for line in (data / "manifest.jsonl").read_text(encoding="utf-8").splitlines())
    return [row["id"] for row in rows]


def _get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)


def _post(url: str, body: bytes, content_type: str, timeout: int) -> dict:
    request = urllib.request.Request(url, data=body, headers={"Content-Type": content_type}, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def _multipart(filename: str, content: bytes) -> tuple[bytes, str]:
    boundary = uuid.uuid4().hex
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            "Content-Type: application/pdf\r\n\r\n").encode()
    return head + content + f"\r\n--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


if __name__ == "__main__":
    main()
