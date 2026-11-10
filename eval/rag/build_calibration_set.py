"""의미 충실도 보정셋 추출과 라벨링 화면 (ADR 0009 3절).

기준선 1회차 해설 문장에서 시드를 고정해 50문장을 층별로 뽑는다.
- other_item_only (20): 인용한 근거가 모두 다른 항목의 근거다 (청크 item_codes에 그 항목이 없다). 기준선에서 찾은 의미 오류 후보
- neighbor_band (10): 공복혈당·혈압 문장. 같은 항목의 다른 구간 문단이 검색 1위가 된 적이 있다 (ADR 0008 맥락)
- rest (20): 나머지에서 무작위
층이 모자라면 rest에서 채운다. 화면은 층을 숨기고 순서를 섞는다 (라벨이 층에 끌려가지 않게).

출력
- eval/calibration/<이름>.jsonl, <이름>.manifest.json: 문장, 항목 정보, 인용 청크 id (근거 원문 없음, 커밋한다)
- eval/.cache/labeling/<이름>.html: 근거 원문이 들어간 라벨링 화면 (포털 글은 공공누리 제4유형이라 커밋하지 않는다)

라벨은 화면에서 내려받아 eval/calibration/<이름>.labels.json으로 둔다. 채점기 결과를 보기 전에 라벨링한다 (눈가림).

사용법 (eval/ 폴더에서): .venv/bin/python -m rag.build_calibration_set [--baseline <결과 파일>] [--name calibration-v1]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
from datetime import datetime
from pathlib import Path

from rag.faithfulness_rubric import AXES, VERSION as RUBRIC_VERSION
from run_record import EVAL_DIR, ROOT

DEFAULT_BASELINE = EVAL_DIR / "results" / "rag" / "20261110-2337_explanation_heldout30-v2.5_baseline.json"
CHUNKS = ROOT / "data" / "guidelines" / "chunks" / "chunks.jsonl"
ITEM_CODE_JAVA = ROOT / "backend" / "report" / "src" / "main" / "java" / "com" / "checkupai" / "report" / "extraction" / "ItemCode.java"
OUT = EVAL_DIR / "calibration"
SCREEN = EVAL_DIR / ".cache" / "labeling"
SEED = 20261111
QUOTA = {"other_item_only": 20, "neighbor_band": 10, "rest": 20}
NEIGHBOR_BAND_CODES = {"FASTING_GLUCOSE", "SYSTOLIC_BP", "DIASTOLIC_BP"}
JUDGMENT_LABELS = {"SUSPECT": "질환의심", "NORMAL_B": "정상B(경계)", "NORMAL_A": "정상A"}


def item_names() -> dict[str, str]:
    """항목 한국어 이름은 Java ItemCode 한 곳에서 관리한다"""
    text = ITEM_CODE_JAVA.read_text(encoding="utf-8")
    return dict(re.findall(r'^\s+([A-Z_]+)\("([^"]+)"', text, re.MULTILINE))


def stratum(item_code: str, sources: list[str], chunks: dict) -> str:
    codes = [chunks[c]["metadata"].get("item_codes") or [] for c in sources]
    if sources and all(c and item_code not in c for c in codes):
        return "other_item_only"
    if item_code in NEIGHBOR_BAND_CODES:
        return "neighbor_band"
    return "rest"


def sample(population: list[dict], rng: random.Random) -> list[dict]:
    by_stratum = {name: [s for s in population if s["stratum"] == name] for name in QUOTA}
    chosen = []
    for name in ("other_item_only", "neighbor_band"):
        chosen += rng.sample(by_stratum[name], min(QUOTA[name], len(by_stratum[name])))
    rest_quota = sum(QUOTA.values()) - len(chosen)  # 앞 층이 모자라면 rest에서 채운다
    chosen += rng.sample(by_stratum["rest"], rest_quota)
    rng.shuffle(chosen)
    return chosen


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--run", type=int, default=1)
    parser.add_argument("--name", default="calibration-v1")
    args = parser.parse_args()
    out_file = OUT / f"{args.name}.jsonl"
    if out_file.exists():
        raise SystemExit(f"이미 있다: {out_file.relative_to(EVAL_DIR)}. 보정셋은 덮어쓰지 않는다 (라벨이 문장 id에 묶여 있다)")

    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    chunks = {row["id"]: row for row in (json.loads(line) for line in CHUNKS.read_text(encoding="utf-8").splitlines())}
    names = item_names()
    population = []
    for report in (r for r in baseline["details"] if r["run"] == args.run):
        for item in report["output"]:
            for index, sentence in enumerate(item["sentences"], start=1):
                population.append({
                    "sentenceId": f"{report['dataset']}/{report['id']}/{item['code']}/{index}",
                    "stratum": stratum(item["code"], sentence["sources"], chunks),
                    "item": {"code": item["code"], "name": names.get(item["code"], item["code"]), "value": item["value"],
                             "unit": item.get("unit"), "judgment": item["judgment"],
                             "judgmentLabel": JUDGMENT_LABELS.get(item["judgment"], item["judgment"]),
                             "criterion": item.get("criterion")},
                    "text": sentence["text"], "sources": sentence["sources"],
                })
    chosen = sample(population, random.Random(SEED))
    rows = [{"calId": f"cal-{i:03d}", **s} for i, s in enumerate(chosen, start=1)]

    OUT.mkdir(parents=True, exist_ok=True)
    out_file.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    manifest = {"name": args.name, "createdAt": datetime.now().isoformat(timespec="seconds"),
                "baseline": baseline["runId"], "run": args.run, "seed": SEED, "rubric": RUBRIC_VERSION,
                "chunksSha256": hashlib.sha256(CHUNKS.read_bytes()).hexdigest()[:16],  # 라벨링 화면에 넣은 원문의 청크 버전
                "population": {name: sum(1 for s in population if s["stratum"] == name) for name in QUOTA},
                "sampled": {name: sum(1 for r in rows if r["stratum"] == name) for name in QUOTA}}
    (OUT / f"{args.name}.manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                                   encoding="utf-8")

    # 라벨링 화면: 층은 넣지 않는다 (눈가림). 근거 원문을 넣으므로 커밋하지 않는 폴더에 쓴다
    screen_rows = [{"calId": r["calId"], "item": r["item"], "text": r["text"],
                    "sources": [{"id": c, "title": chunks[c]["metadata"].get("title"),
                                 "section": chunks[c]["metadata"].get("section"),
                                 "publisher": chunks[c]["metadata"].get("publisher"), "text": chunks[c]["text"]}
                                for c in r["sources"]]} for r in rows]
    data = {"set": args.name, "rubricVersion": RUBRIC_VERSION, "axes": AXES, "rows": screen_rows}
    html = (Path(__file__).parent / "calibration_labeling.html").read_text(encoding="utf-8")
    html = html.replace("__DATA__", json.dumps(data, ensure_ascii=False).replace("</", "<\\/"))
    SCREEN.mkdir(parents=True, exist_ok=True)
    screen = SCREEN / f"{args.name}.html"
    screen.write_text(html, encoding="utf-8")
    print(f"모집단 {manifest['population']} → 추출 {manifest['sampled']}")
    print(f"저장: {out_file.relative_to(EVAL_DIR)}, {screen.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
