"""추출 평가 실행: 합성 결과지를 추출 API에 보내고 정답과 비교한다.

사용법 (eval/ 폴더에서, 앱이 떠 있어야 한다):
    .venv/bin/python -m extraction.run_eval --subset smoke    # 6개, 로컬 CPU 약 1시간
    .venv/bin/python -m extraction.run_eval --subset full     # 30개, 로컬 CPU 약 5시간

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

from .metrics import Score, score_document, score_failed_document

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
    parser.add_argument("--format", choices=["pdf", "txt"], default="pdf", help="API에 보낼 입력 형식")
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=1800, help="보고서 하나당 최대 대기 초")
    args = parser.parse_args()

    info = _get_json(f"{args.api}/api/reports/extract/info")
    ids = SMOKE_IDS if args.subset == "smoke" else _all_ids(args.data)
    print(f"평가 시작: {info} | {args.subset} {len(ids)}개 | 입력 {args.format}", flush=True)

    total, per_template, documents = Score(), {}, []
    for index, report_id in enumerate(ids, start=1):
        expected = json.loads((args.data / f"{report_id}.expected.json").read_text(encoding="utf-8"))
        content = (args.data / f"{report_id}.{args.format}").read_bytes()
        response, cached = _extract(args, info, report_id, content)

        if "error" in response:
            score = score_failed_document(expected["items"], response["error"])
        else:
            score = score_document(expected["items"], response["report"].get("items") or [])

        template = report_id.split("-")[0]
        total = total.add(score)
        per_template[template] = per_template.get(template, Score()).add(score)
        metrics = response.get("metrics") or {}
        documents.append({"id": report_id, "cached": cached, **score.summary(),
                          "metrics": metrics, "error": response.get("error"), "errors": score.errors})
        print(f"[{index}/{len(ids)}] {report_id} F1={score.f1:.3f} (TP {score.tp} / FP {score.fp} / FN {score.fn})"
              f" {'캐시' if cached else str(metrics.get('latencyMs', '-')) + 'ms'}"
              + (f" 오류: {response['error'][:80]}" if "error" in response else ""), flush=True)

    _save(args, info, ids, total, per_template, documents)


def _extract(args, info: dict, report_id: str, content: bytes) -> tuple[dict, bool]:
    key_source = json.dumps(info, sort_keys=True) + args.format
    key = hashlib.sha256(key_source.encode() + content).hexdigest()[:32]
    cache_file = CACHE_DIR / f"{key}.json"
    if cache_file.exists():
        return json.loads(cache_file.read_text(encoding="utf-8")), True

    started = time.monotonic()
    try:
        if args.format == "txt":
            response = _post(f"{args.api}/api/reports/extract", content,
                             "text/plain;charset=UTF-8", args.timeout)
        else:
            body, content_type = _multipart(f"{report_id}.pdf", content)
            response = _post(f"{args.api}/api/reports/extract", body, content_type, args.timeout)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        detail = e.read().decode("utf-8", "replace")[:500] if isinstance(e, urllib.error.HTTPError) else ""
        return {"error": f"{type(e).__name__}: {e} {detail}".strip(),
                "elapsedMs": int((time.monotonic() - started) * 1000)}, False

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_file.write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
    return response, False


def _save(args, info: dict, ids: list[str], total: Score, per_template: dict, documents: list[dict]) -> None:
    calls = [d["metrics"] for d in documents if d["metrics"]]
    avg = lambda key: round(sum(c.get(key) or 0 for c in calls) / len(calls)) if calls else None
    summary = {
        "runAt": datetime.now().isoformat(timespec="seconds"),
        **info,
        "subset": args.subset,
        "format": args.format,
        "documents": len(ids),
        "failed": sum(1 for d in documents if d["error"]),
        "itemF1": total.summary(),
        "byTemplate": {t: s.summary() for t, s in sorted(per_template.items())},
        "avgLatencyMs": avg("latencyMs"),
        "avgPromptTokens": avg("promptTokens"),
        "avgCompletionTokens": avg("completionTokens"),
    }

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    model = str(info.get("model", "unknown")).replace(":", "-").replace("/", "-")
    out = RESULTS_DIR / f"{stamp}_{info.get('pipeline')}_{model}_{args.subset}_{args.format}.json"
    out.write_text(json.dumps({"summary": summary, "documents": documents}, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")

    history = RESULTS_DIR / "history.md"
    if not history.exists():
        history.write_text("# 추출 평가 기록\n\n대표 지표: 항목 F1 (code + value 일치). 정밀도 P, 재현율 R.\n\n"
                           "| 실행 시각 | 파이프라인 | 모델 | 프롬프트 | 평가셋 | 입력 | F1 | P | R | 실패 | 평균 지연 | 평균 토큰(입/출) |\n"
                           "|---|---|---|---|---|---|---|---|---|---|---|---|\n", encoding="utf-8")
    f1 = summary["itemF1"]
    latency = f"{summary['avgLatencyMs'] / 1000:.0f}초" if summary["avgLatencyMs"] else "-"
    with history.open("a", encoding="utf-8") as f:
        f.write(f"| {summary['runAt']} | {info.get('pipeline')} | {info.get('model')} | {info.get('promptVersion')} "
                f"| {args.subset}({len(ids)}) | {args.format} | **{f1['f1']:.3f}** | {f1['precision']:.3f} "
                f"| {f1['recall']:.3f} | {summary['failed']} | {latency} "
                f"| {summary['avgPromptTokens']}/{summary['avgCompletionTokens']} |\n")

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"저장: {out.relative_to(EVAL_DIR)}")


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
