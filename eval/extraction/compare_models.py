"""모델 × 파이프라인 조건 비교표 (2단계, ADR 0005).

한 모델을 validated 파이프라인으로 한 번 실행하면 세 조건을 모두 얻는다. 같은 LLM 출력이므로 짝 비교다.
- 1차만: 1차 추출 출력 그대로 (baseline 파이프라인과 같은 프롬프트)
- 검증만: 1차 출력을 원문 대조 검증만 한 결과 (재요청 없음). 재생 비교로 계산
- 검증+재요청: 실제 최종 결과. 재생 비교로 현재 검증기를 적용 (재요청 입력이 달라진 보고서는 근사치로 표시)

설정 파일(JSON) 형식:
{
  "prices": {"claude-haiku-5-5": [0.10, 0.50]},          # 100만 토큰당 달러 (입력, 출력). 없으면 0 (로컬 모델)
  "runs": [
    {"model": "qwen3:4b", "group": "개발셋", "data": "../data/synthetic/extraction/v1",
     "results": ["results/extraction/<결과>.json", ...],   # 같은 보고서가 여럿이면 뒤의 것을 쓴다
     "replayed": ".cache/replay/<이름>/out"}                # extraction.replay + ./gradlew :report:replay 출력
  ]
}

사용법 (eval/ 폴더에서): .venv/bin/python -m extraction.compare_models <설정 파일>
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

from .metrics import METRIC_VERSION, Score, score_document, score_failed_document, score_fields

CONDITIONS = ("1차만", "검증만", "검증+재요청")


def compare(config_path: Path) -> str:
    config = json.loads(config_path.read_text(encoding="utf-8"))
    prices = config.get("prices", {})
    table: dict[tuple[str, str], dict] = {}
    for run in config["runs"]:
        key = (run["model"], run["group"])
        cell = table.setdefault(key, {"scores": {c: Score() for c in CONDITIONS}, "latency": defaultdict(list),
                                      "cost": defaultdict(list), "approximate": 0, "documents": 0, "failed": 0})
        price_in, price_out = prices.get(run["model"], (0.0, 0.0))
        documents = {}
        for results in run["results"]:
            for doc in json.loads(Path(results).read_text(encoding="utf-8"))["documents"]:
                documents[doc["id"]] = doc
        for report_id, doc in sorted(documents.items()):
            expected = json.loads((Path(run["data"]) / f"{report_id}.expected.json").read_text(encoding="utf-8"))["items"]
            cell["documents"] += 1
            replayed = Path(run["replayed"]) / f"{report_id}.json"
            if doc.get("error") or not replayed.exists():
                cell["failed"] += 1
                for condition in CONDITIONS:
                    cell["scores"][condition] = cell["scores"][condition].add(
                        score_failed_document(expected, doc.get("error") or "재생 결과 없음"))
                continue
            out = json.loads(replayed.read_text(encoding="utf-8"))
            first_items = (doc.get("diagnostics") or {}).get("firstPassItems") or []
            for condition, items in zip(CONDITIONS, (first_items, out["validatedItems"], out["items"])):
                cell["scores"][condition] = cell["scores"][condition].add(score_document(expected, items or []))
            cell["approximate"] += 1 if out["retryInputChanged"] else 0

            metrics = doc.get("metrics") or {}
            total_cost = _cost(metrics.get("promptTokens"), metrics.get("completionTokens"), price_in, price_out)
            first_cost = _cost(metrics.get("firstPassPromptTokens"), metrics.get("firstPassCompletionTokens"),
                               price_in, price_out)
            first_latency = metrics.get("firstPassLatencyMs")
            # 검증은 코드만 돌므로 지연·비용이 1차와 같다
            for condition, latency, cost in (("1차만", first_latency, first_cost), ("검증만", first_latency, first_cost),
                                             ("검증+재요청", metrics.get("latencyMs"), total_cost)):
                if latency is not None:
                    cell["latency"][condition].append(latency)
                if cost is not None:
                    cell["cost"][condition].append(cost)

    lines = [f"지표 {METRIC_VERSION}. 비용은 보고서 1개당 달러, 지연은 보고서 1개당 평균. "
             "'-'는 그 실행에서 따로 기록하지 않은 값이다(1차만의 지연·비용은 2026-11-10부터 기록).", "",
             "| 모델 | 평가셋 | 조건 | F1 | P | R | 필드 정확도 (단위/참고치/판정) | 지연 | 비용 | 보고서 (근사/실패) |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for (model, group), cell in table.items():
        for condition in CONDITIONS:
            score = cell["scores"][condition]
            s = score.summary()
            fields = "/".join(f"{v['accuracy'] * 100:.1f}" if v["accuracy"] is not None else "-"
                              for v in score_fields(score.matches).values())
            latency = _mean(cell["latency"][condition], cell["documents"] - cell["failed"])
            cost = _mean(cell["cost"][condition], cell["documents"] - cell["failed"])
            approx = f"{cell['approximate']}/{cell['failed']}" if condition == "검증+재요청" else f"-/{cell['failed']}"
            lines.append(f"| {model} | {group} | {condition} | **{s['f1']:.3f}** | {s['precision']:.3f} "
                         f"| {s['recall']:.3f} | {fields} "
                         f"| {f'{latency / 1000:.1f}초' if latency is not None else '-'} "
                         f"| {f'${cost:.4f}' if cost is not None else '-'} "
                         f"| {cell['documents']} ({approx}) |")
    return "\n".join(lines)


def _cost(prompt_tokens, completion_tokens, price_in, price_out):
    if prompt_tokens is None or completion_tokens is None:
        return None
    return (prompt_tokens * price_in + completion_tokens * price_out) / 1_000_000


def _mean(values: list, expected_count: int):
    """모든 보고서에 값이 있을 때만 평균을 낸다. 일부만 있으면 오해를 부르므로 '-'로 둔다."""
    return sum(values) / len(values) if values and len(values) == expected_count else None


def main() -> None:
    parser = argparse.ArgumentParser(description="모델 × 파이프라인 조건 비교표")
    parser.add_argument("config", type=Path)
    args = parser.parse_args()
    print(compare(args.config))


if __name__ == "__main__":
    main()
