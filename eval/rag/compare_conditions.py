"""조건 짝비교 (ADR 0008 4절 4번). 같은 고정 추출로 조건 하나만 바꾼 두 해설 평가를 비교한다.

- 전체 수치와 함께, **근거 목록이 바뀐 항목**만의 수치를 낸다. 바뀐 항목만 보면 선택 편향이 생기고,
  전체만 보면 효과가 희석된다 (Codex 리뷰, devlog 2026-11-10).
- 근거 목록이 바뀐 항목: 같은 보고서·항목에서 허락된 근거(evidence) 목록이나 문장 출처(LLM/코드)가 다른 항목.
  검색은 결정적이므로 1회차 결과로 정한다.
- 충실도 채점 결과(rag.run_faithfulness_judge --explanation ...)를 주면 통과율과 치료·약 관문도 같은 방식으로 나눈다.
- 해설은 조건당 2회라 변동 폭을 가늠하는 수준이다. 회차마다 따로 적는다.

사용법 (eval/ 폴더에서):
    .venv/bin/python -m rag.compare_conditions --base results/rag/<기준>.json --new results/rag/<조건>.json \\
        [--base-judge results/faithfulness/<기준 채점>.json --new-judge results/faithfulness/<조건 채점>.json]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import run_record
from rag.faithfulness_rubric import passes
from run_record import EVAL_DIR


def item_signatures(record: dict) -> dict[tuple, tuple]:
    """(데이터셋, 보고서, 항목) → (근거 목록, 문장 출처). 1회차 기준"""
    signatures = {}
    for report in (r for r in record["details"] if r["run"] == 1):
        for it in report["output"]:
            if it.get("evidence") is None:
                raise SystemExit(f"{record['runId']}에 항목별 근거 목록이 없다. 5단계 4번 이후 코드로 다시 잰 결과가 필요하다")
            origins = tuple(sorted({s.get("origin", "LLM") for s in it["sentences"]}))
            signatures[(report["dataset"], report["id"], it["code"])] = (tuple(it["evidence"]), origins)
    return signatures


def changed_items(base: dict, new: dict) -> set[tuple]:
    a, b = item_signatures(base), item_signatures(new)
    return {key for key in a.keys() | b.keys() if a.get(key) != b.get(key)}


def explanation_numbers(record: dict, only: set[tuple] | None) -> list[dict]:
    """회차별: 대상 항목 수, LLM 문장 통과, 근거 없음, 진료 권고 (only가 있으면 그 항목만)"""
    out = []
    for run in sorted({r["run"] for r in record["details"]}):
        items = sentences = kept_llm = no_evidence = templated = 0
        expected = advised = 0
        for report in (r for r in record["details"] if r["run"] == run):
            for it in report["output"]:
                key = (report["dataset"], report["id"], it["code"])
                if only is not None and key not in only:
                    continue
                items += 1
                no_evidence += 1 if it["noEvidence"] else 0
                templated += 1 if any(s.get("origin") == "CODE" for s in it["sentences"]) else 0
                sentences += len(it["sentences"])
                kept_llm += sum(1 for s in it["sentences"] if s.get("origin", "LLM") == "LLM")
            suspect = [c for c in report["expectedSuspect"] if only is None or (report["dataset"], report["id"], c) in only]
            missed = {m["code"] for m in report["misses"]}
            expected += len(suspect)
            advised += sum(1 for c in suspect if c not in missed)
        out.append({"run": run, "items": items, "sentences": sentences, "llmSentences": kept_llm,
                    "codeTemplatedItems": templated, "noEvidenceItems": no_evidence,
                    "careAdvice": f"{advised}/{expected}"})
    return out


def judge_numbers(judge: dict, only: set[tuple] | None) -> list[dict]:
    """채점 결과 id는 r{회차}/{데이터셋}/{보고서}/{항목}/{번호}"""
    out = []
    by_run: dict[int, list[dict]] = {}
    for d in judge["details"]:
        run, dataset, report_id, code, _ = d["id"].split("/")
        if only is not None and (dataset, report_id, code) not in only:
            continue
        by_run.setdefault(int(run[1:]), []).extend(r["result"] for r in d["repeats"] if "result" in r)
    for run, results in sorted(by_run.items()):
        n = len(results)
        out.append({"run": run, "judged": n, "passed": sum(1 for r in results if passes(r)),
                    "passRate": round(sum(1 for r in results if passes(r)) / n, 3) if n else None,
                    "unsupported": sum(1 for r in results if r["support"] == "unsupported"),
                    "partial": sum(1 for r in results if r["support"] == "partial"),
                    "bandWrong": sum(1 for r in results if r["band"] == "wrong"),
                    "treatmentPresent": sum(1 for r in results if r["treatment"] == "present")})
    return out


def check_judge_pair(judge: dict, explanation: dict, side: str) -> None:
    """채점 파일이 그 해설 실행을 채점한 것인지 확인한다. 키(회차, 보고서, 항목)만으로 이으면
    다른 조건의 채점을 붙여도 그럴듯한 숫자가 나온다 (Codex 리뷰, 2026-11-11)"""
    target = judge.get("dataset", {})
    if target.get("source") != "explanation" or target.get("name") != explanation["runId"]:
        raise SystemExit(f"{side} 채점 파일({judge.get('runId')})의 대상은 {target.get('name')}인데, "
                         f"{side} 해설 실행은 {explanation['runId']}다. 같은 실행을 채점한 파일을 준다")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--new", type=Path, required=True)
    parser.add_argument("--base-judge", type=Path)
    parser.add_argument("--new-judge", type=Path)
    parser.add_argument("--label", required=True, help="바꾼 조건 이름 (예: item-filter)")
    args = parser.parse_args()
    load = lambda p: json.loads((p if p.is_absolute() else EVAL_DIR / p).read_text(encoding="utf-8"))
    base, new = load(args.base), load(args.new)
    if base["dataset"].get("extraction") != new["dataset"].get("extraction"):
        raise SystemExit("고정 추출이 다르다. 같은 추출로 잰 두 결과만 비교한다")
    changed = changed_items(base, new)
    total_items = len(item_signatures(base).keys() | item_signatures(new).keys())
    report = {"changedItems": len(changed), "totalItems": total_items,
              "explanation": {side: {"all": explanation_numbers(rec, None), "changed": explanation_numbers(rec, changed)}
                              for side, rec in (("base", base), ("new", new))}}
    if bool(args.base_judge) != bool(args.new_judge):
        raise SystemExit("채점 파일은 기준과 조건 둘 다 주거나 둘 다 주지 않는다")
    if args.base_judge and args.new_judge:
        judges = {"base": load(args.base_judge), "new": load(args.new_judge)}
        check_judge_pair(judges["base"], base, "기준")
        check_judge_pair(judges["new"], new, "조건")
        if judges["base"]["config"]["setting"] != judges["new"]["config"]["setting"]:
            raise SystemExit("두 채점의 설정(thinking)이 다르다. 같은 설정으로 채점한 결과끼리 비교한다")
        report["faithfulness"] = {side: {"all": judge_numbers(j, None), "changed": judge_numbers(j, changed)}
                                  for side, j in judges.items()}
    print(f"근거 목록이 바뀐 항목: {len(changed)} / {total_items}")
    print(json.dumps(report, ensure_ascii=False, indent=1))

    run_id = run_record.new_run_id("comparison", args.label)
    out = run_record.save(run_id, "comparison", "comparison", label=args.label,
                          dataset={"name": base["dataset"]["name"], "base": base["runId"], "new": new["runId"]},
                          config={"base": base["config"], "new": new["config"]}, summary=report,
                          headline={"changedItems": len(changed)},
                          details=sorted("/".join(key) for key in changed))
    print(f"저장: {out.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
