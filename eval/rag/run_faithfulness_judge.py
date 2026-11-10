"""의미 충실도 채점 실행 (ADR 0009). 채점 자체는 faithfulness_judge.py, 일치도 계산은 agreement.py에 있다.

대상은 두 가지다.
- 보정셋 (--calibration calibration-v1): 50문장. 설정(off, low)마다 3회 돌려 자기 일치도와 비용을 본다.
  사람 라벨과의 κ는 rag.calibration_agreement가 따로 계산한다 (이 스크립트는 사람 라벨을 읽지 않는다. 눈가림).
- 해설 평가 결과 (--explanation <결과 파일> --runs 1,2): 기준선이나 개선 조건의 모든 문장을 채점한다.

결과는 eval/results/faithfulness/<runId>.json (공통 형식)과 eval/results/index.jsonl에 남는다.
ANTHROPIC_API_KEY는 저장소 루트 .env에서 읽어 이 프로세스 환경에만 넣는다 (값은 출력하지 않는다).

사용법 (eval/ 폴더에서):
    .venv/bin/python -m rag.run_faithfulness_judge --calibration calibration-v1 --setting off --repeats 3
    .venv/bin/python -m rag.run_faithfulness_judge --calibration calibration-v1 --setting low --limit 3 --label trial
    .venv/bin/python -m rag.run_faithfulness_judge --explanation results/rag/<기준선>.json --runs 1,2 --setting off
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import run_record
from rag.agreement import self_consistency
from rag.build_calibration_set import CHUNKS, JUDGMENT_LABELS, item_names, stratum
from rag.faithfulness_judge import MODEL, PROMPT_VERSION, SETTINGS, call_judge
from rag.faithfulness_rubric import VERSION as RUBRIC_VERSION, passes
from run_record import EVAL_DIR, ROOT


def load_env_key() -> None:
    """.env의 ANTHROPIC_API_KEY만 이 프로세스 환경에 넣는다. 값은 어디에도 출력하지 않는다"""
    if os.environ.get("ANTHROPIC_API_KEY"):
        return
    env = ROOT / ".env"
    for line in env.read_text(encoding="utf-8").splitlines() if env.exists() else []:
        name, _, value = line.partition("=")
        if name.strip() == "ANTHROPIC_API_KEY" and value.strip():
            os.environ["ANTHROPIC_API_KEY"] = value.strip().strip('"').strip("'")
            return
    raise SystemExit("ANTHROPIC_API_KEY가 환경변수나 .env에 없다")


def chunks_sha256() -> str:
    """근거 청크 파일 해시 앞 16자 (평가 API의 chunksSha256과 같은 계산)"""
    return hashlib.sha256(CHUNKS.read_bytes()).hexdigest()[:16]


def check_chunks(expected: str | None, what: str) -> None:
    """채점기는 채점 시점의 청크 파일에서 id로 원문을 찾는다. 대상이 만들어질 때의 청크와 다르면
    같은 id에 다른 원문이 붙은 채로 조용히 채점된다 (Codex 리뷰, 2026-11-11). 그래서 해시가 다르면 멈춘다"""
    if expected is None:
        print(f"주의: {what}에 청크 해시가 없다 (5단계 4번 이전 기록). 청크 파일이 그때와 같다고 가정한다")
    elif expected != chunks_sha256():
        raise SystemExit(f"청크 파일이 다르다: {what}는 {expected}, 지금 파일은 {chunks_sha256()}. "
                         "그때의 청크로 다시 만든 뒤(rag.build_chunks) 채점한다")


def calibration_targets(name: str, chunks: dict) -> list[dict]:
    rows = [json.loads(line) for line in (EVAL_DIR / "calibration" / f"{name}.jsonl").read_text(encoding="utf-8").splitlines()]
    return [{"id": r["calId"], "stratum": r["stratum"], "item": r["item"], "text": r["text"],
             "sources": [source(chunks, c) for c in r["sources"]]} for r in rows]


def explanation_targets(path: Path, runs: list[int], chunks: dict) -> list[dict]:
    record = json.loads(path.read_text(encoding="utf-8"))
    names = item_names()
    targets = []
    for report in (r for r in record["details"] if r["run"] in runs):
        for it in report["output"]:
            item = {"code": it["code"], "name": names.get(it["code"], it["code"]), "value": it["value"],
                    "unit": it.get("unit"), "judgment": it["judgment"],
                    "judgmentLabel": JUDGMENT_LABELS.get(it["judgment"], it["judgment"]), "criterion": it.get("criterion")}
            for index, s in enumerate(it["sentences"], start=1):
                targets.append({"id": f"r{report['run']}/{report['dataset']}/{report['id']}/{it['code']}/{index}",
                                "stratum": stratum(it["code"], s["sources"], chunks), "origin": s.get("origin", "LLM"),
                                "item": item, "text": s["text"],
                                "sources": [source(chunks, c) for c in s["sources"]]})
    return targets


def source(chunks: dict, chunk_id: str) -> dict:
    meta = chunks[chunk_id]["metadata"]
    return {"id": chunk_id, "text": chunks[chunk_id]["text"], "publisher": meta.get("publisher"),
            "section": meta.get("section")}


def summarize(details: list[dict], repeats: int) -> dict:
    calls = [r for d in details for r in d["repeats"]]
    ok = [r for r in calls if "result" in r]
    results = [r["result"] for r in ok]
    n = len(results)
    by_stratum = {}
    for name in sorted({d["stratum"] for d in details}):
        rs = [r["result"] for d in details if d["stratum"] == name for r in d["repeats"] if "result" in r]
        by_stratum[name] = {"judged": len(rs), "pass": sum(1 for r in rs if passes(r)),
                            "support": dict(Counter(r["support"] for r in rs))}
    return {
        "calls": len(calls), "judged": n, "errors": len(calls) - n,
        "support": dict(Counter(r["support"] for r in results)),
        "supportRaw": dict(Counter(r["supportRaw"] for r in results)),
        "passRate": round(sum(1 for r in results if passes(r)) / n, 3) if n else None,
        "passed": sum(1 for r in results if passes(r)),
        "bandWrong": sum(1 for r in results if r["band"] == "wrong"),
        "treatmentPresent": sum(1 for r in results if r["treatment"] == "present"),  # 별도 관문, 목표 0건
        "quoteVerifyFailed": sum(1 for r in results if not r["quoteVerified"]),
        "selfConsistency": {axis: self_consistency([[r["result"][axis] for r in d["repeats"] if "result" in r]
                                                    for d in details]) for axis in ("support", "band", "treatment")}
        if repeats > 1 else None,
        "byStratum": by_stratum,
        "costUsd": round(sum(r["costUsd"] for r in calls), 4),
        "avgCostUsd": round(sum(r["costUsd"] for r in calls) / len(calls), 5) if calls else None,
        "avgLatencyMs": round(sum(r["latencyMs"] for r in calls) / len(calls)) if calls else None,
        "avgTokens": {k: round(sum(r["usage"][k] for r in calls) / len(calls)) for k in
                      ("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")}
        if calls else None,
        "fromCache": sum(1 for r in calls if r.get("cached")),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--calibration", help="eval/calibration/ 아래 보정셋 이름")
    target.add_argument("--explanation", type=Path, help="해설 평가 결과 파일 (eval/ 기준 경로)")
    parser.add_argument("--runs", default="1,2", help="--explanation의 회차")
    parser.add_argument("--setting", choices=list(SETTINGS), required=True)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--limit", type=int, help="앞에서 N문장만 (시험 호출)")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--label", default="run")
    args = parser.parse_args()

    load_env_key()
    import anthropic  # SDK는 실제 호출할 때만 필요하다
    client = anthropic.Anthropic()
    git = run_record.git_state()
    chunks = {row["id"]: row for row in (json.loads(line) for line in CHUNKS.read_text(encoding="utf-8").splitlines())}
    if args.calibration:
        manifest = json.loads((EVAL_DIR / "calibration" / f"{args.calibration}.manifest.json").read_text(encoding="utf-8"))
        check_chunks(manifest.get("chunksSha256"), f"보정셋 {args.calibration}")
        targets, dataset = calibration_targets(args.calibration, chunks), {"name": args.calibration, "source": "calibration"}
    else:
        runs = [int(r) for r in args.runs.split(",")]
        source_path = args.explanation if args.explanation.is_absolute() else EVAL_DIR / args.explanation
        explanation_record = json.loads(source_path.read_text(encoding="utf-8"))
        check_chunks(explanation_record["config"].get("chunksSha256"), f"해설 실행 {explanation_record['runId']}")
        targets = explanation_targets(source_path, runs, chunks)
        dataset = {"name": explanation_record["runId"], "source": "explanation", "runs": runs}
    if args.limit:
        targets = targets[:args.limit]
    dataset["sentences"] = len(targets)
    print(f"채점: {dataset['name']} {len(targets)}문장 x {args.repeats}회 | 설정 {args.setting} | {MODEL}", flush=True)

    jobs = [(t, repeat) for t in targets for repeat in range(1, args.repeats + 1)]
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        outcomes = list(pool.map(lambda job: _judge(client, args.setting, *job), jobs))
    details = []
    for t in targets:
        reps = [o for (job_t, _), o in zip(jobs, outcomes) if job_t is t]
        details.append({"id": t["id"], "stratum": t["stratum"], "origin": t.get("origin", "LLM"),
                        "itemCode": t["item"]["code"], "text": t["text"],
                        "sources": [s["id"] for s in t["sources"]], "repeats": reps})
    summary = summarize(details, args.repeats)
    print(json.dumps({k: v for k, v in summary.items() if k != "byStratum"}, ensure_ascii=False))

    run_id = run_record.new_run_id("faithfulness", f"{dataset['name']}_{args.setting}_{args.label}")
    out = run_record.save(
        run_id, "faithfulness", "faithfulness", label=args.label, dataset=dataset,
        config={"model": MODEL, "rubric": RUBRIC_VERSION, "prompt": PROMPT_VERSION, "setting": args.setting,
                "chunksSha256": chunks_sha256(),
                "settingDetail": SETTINGS[args.setting], "repeats": args.repeats},
        summary=summary,
        headline={"passRate": summary["passRate"], "treatmentPresent": summary["treatmentPresent"],
                  "selfConsistency.support": (summary["selfConsistency"] or {}).get("support"),
                  "costUsd": summary["costUsd"]},
        details=details, git=git)
    print(f"저장: {out.relative_to(EVAL_DIR)}")


def _judge(client, setting: str, target: dict, repeat: int) -> dict:
    try:
        outcome = call_judge(client, setting, repeat, target["item"], target["text"], target["sources"])
    except Exception as e:  # 한 문장 실패로 전체를 멈추지 않는다. 오류로 세고 남긴다
        return {"repeat": repeat, "error": f"{type(e).__name__}: {str(e)[:200]}", "costUsd": 0, "latencyMs": 0,
                "usage": {k: 0 for k in ("input_tokens", "output_tokens", "cache_creation_input_tokens",
                                         "cache_read_input_tokens")}}
    print(f"  {target['id']} #{repeat}: " + (outcome["result"]["support"] if "result" in outcome else outcome["error"]),
          flush=True)
    return {"repeat": repeat, **outcome}


if __name__ == "__main__":
    main()
