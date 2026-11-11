"""채점기 검증용 오류 심은 세트를 만든다 (ADR 0010 5절, ADR 0009 "채점기 검증"). 모델을 부르지 않는다.

바탕 문장은 보정셋에서 사람 라벨과 채점기 모든 회차가 '지지, 치료 없음'으로 본 문장이다.
오류의 종류, 치환 목록, 통과 기준은 rag/seeded_errors.py에 있다.

출력
- eval/calibration/<이름>.jsonl, <이름>.manifest.json: 보정셋과 같은 형식이다 (근거 원문 없음, 커밋한다).
  그래서 채점은 기존 실행기로 한다: rag.run_faithfulness_judge --calibration <이름> --setting off
- eval/.cache/labeling/<이름>.html: 검토 화면. 바탕 문장과 바뀐 곳, 근거 원문을 함께 보여 준다
  (포털 글은 공공누리 제4유형이라 커밋하지 않는다). 사용자가 채점 전에 훑어본다.

사용법 (eval/ 폴더에서):
    .venv/bin/python -m rag.build_seeded_set [--name seeded-v1] [--overwrite]
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
from collections import Counter
from datetime import datetime
from pathlib import Path

from rag.build_calibration_set import CHUNKS, OUT, SCREEN
from rag.faithfulness_rubric import VERSION as RUBRIC_VERSION
from rag.seeded_errors import CRITERIA, SEED, TYPES, build_rows, clean_bases
from run_record import EVAL_DIR, ROOT

BASE_SET = "calibration-v1"
DEFAULT_JUDGE = EVAL_DIR / "results" / "faithfulness" / "20261111-0216_faithfulness_calibration-v1_off_calibration-v1.2.json"
CURATED_ROWS = ROOT / "data" / "guidelines" / "curated" / "mohw-2026-6-annex4.jsonl"
CRITERIA_ROW_SECTION = "검사항목별 판정기준"


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def row_codes() -> dict[str, list[str]]:
    """기준표 행 청크 id → 항목 코드 (커밋된 고시 표에서 읽는다. 청크 파일이 없어도 세트를 만들 수 있다)"""
    return {r["id"]: r["item_codes"] for r in read_jsonl(CURATED_ROWS) if r["section"] == CRITERIA_ROW_SECTION}


def load_bases(judge_path: Path) -> tuple[list[dict], dict]:
    judge = json.loads(judge_path.read_text(encoding="utf-8"))
    if judge["dataset"].get("name") != BASE_SET or judge["config"]["rubric"] != RUBRIC_VERSION:
        raise SystemExit(f"바탕 문장을 고를 채점 결과는 {BASE_SET}를 기준 {RUBRIC_VERSION}로 채점한 것이어야 한다")
    labels_file = json.loads((OUT / f"{BASE_SET}.labels-{RUBRIC_VERSION}.json").read_text(encoding="utf-8"))
    labels = {row["calId"]: row for row in labels_file["labels"]}
    return clean_bases(read_jsonl(OUT / f"{BASE_SET}.jsonl"), labels, judge["details"]), judge


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", default="seeded-v1")
    parser.add_argument("--judge", type=Path, default=DEFAULT_JUDGE, help="바탕 문장을 고를 보정 채점 결과")
    parser.add_argument("--overwrite", action="store_true", help="채점을 돌리기 전에만 쓴다 (채점 결과가 문장 id에 묶인다)")
    args = parser.parse_args()
    out_file = OUT / f"{args.name}.jsonl"
    if out_file.exists() and not args.overwrite:
        raise SystemExit(f"이미 있다: {out_file.relative_to(EVAL_DIR)}. 채점 전이라면 --overwrite로 다시 만든다")

    bases, judge = load_bases(args.judge if args.judge.is_absolute() else EVAL_DIR / args.judge)
    rows = build_rows(bases, row_codes())
    counts = Counter(r["stratum"] for r in rows)
    out_file.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    manifest = {"name": args.name, "createdAt": datetime.now().isoformat(timespec="seconds"), "baseSet": BASE_SET,
                "baseSelection": {"judgeRun": judge["runId"], "labels": f"{BASE_SET}.labels-{RUBRIC_VERSION}.json",
                                  "rule": "사람 라벨과 채점기 모든 회차가 지지, 치료 없음", "bases": len(bases)},
                "seed": SEED, "rubric": RUBRIC_VERSION,
                "chunksSha256": hashlib.sha256(CHUNKS.read_bytes()).hexdigest()[:16],  # 실행기가 채점 전에 대조한다
                "sentences": {kind: counts.get(kind, 0) for kind in TYPES}, "criteria": CRITERIA}
    (OUT / f"{args.name}.manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                                   encoding="utf-8")
    print(f"바탕 문장 {len(bases)}개 → {len(rows)}문장 {dict(manifest['sentences'])}")
    print(f"저장: {out_file.relative_to(EVAL_DIR)}")
    write_screen(args.name, rows, {b["calId"]: b for b in bases})


def write_screen(name: str, rows: list[dict], bases: dict[str, dict]) -> None:
    """검토 화면: 종류별로 바탕 문장과 바뀐 곳, 인용한 근거 원문을 보여 준다. 근거 원문이 들어가므로 커밋하지 않는다"""
    chunks = {c["id"]: c for c in read_jsonl(CHUNKS)}
    esc = html.escape

    def marked(row: dict, base_text: str) -> str:
        """바꾸거나 덧붙인 곳을 표시한 문장"""
        change = row["seed"]["change"]
        if change["kind"] == "replace":
            at = base_text.index(change["find"])
            return (f"{esc(base_text[:at])}<mark>{esc(change['replace'])}</mark>"
                    f"{esc(base_text[at + len(change['find']):])}")
        if change["kind"] == "append":
            return f"{esc(base_text)} <mark>{esc(change['text'])}</mark>"
        return esc(row["text"])

    def sources(ids: list[str], cls: str = "") -> str:
        return "".join(f'<div class="src {cls}"><b>{esc(i)}</b> {esc(chunks[i]["text"])}</div>' for i in ids)

    parts = []
    for kind, spec in TYPES.items():
        of_kind = [r for r in rows if r["stratum"] == kind]
        if kind == "base":
            parts.append(f"<h2>{esc(spec['name'])} ({len(of_kind)}문장)</h2><p class=note>바탕 문장 그대로다. "
                         "이미 통과한 문장이라 통과가 당연하다. 배선이 맞는지만 본다.</p>")
            continue
        expected = {"wrong": "판정 구간: 틀림", "present": "치료·약: 있음", "none": "치료·약: 없음 (잡으면 안 된다)",
                    "not_supported": "뒷받침: 지지가 아님", False: "통과하면 안 된다"}[spec["expected"]]
        parts.append(f"<h2>{esc(spec['name'])} ({len(of_kind)}문장)</h2><p class=note>기대 판정 — {esc(expected)}</p>")
        for row in of_kind:
            base, item, change = bases[row["seed"]["base"]], row["item"], row["seed"]["change"]
            info = (f"{item['name']} | 결과값 {item['value']}{' ' + item['unit'] if item.get('unit') else ''} | "
                    f"{item['judgmentLabel']} | 성별 {item.get('sex') or '모름'}")
            body = f'<div class=meta>{esc(row["calId"])} ← {esc(base["calId"])} · {esc(info)}</div>'
            body += f'<div class=crit>판정 기준: {esc(item.get("criterion") or "없음")}</div>'
            if change["kind"] == "replace":
                body += f'<div class=before>원래: {esc(base["text"])}</div>'
                body += f'<div class=after>바꾼 문장: {marked(row, base["text"])}</div>'
                body += f'<div class=diff>{esc(change["find"])} → {esc(change["replace"])}</div>'
            else:
                body += f'<div class=after>{marked(row, base["text"])}</div>'
            if change["kind"] == "sources":
                body += "<div class=label>원래 인용</div>" + sources(change["from"], "old")
                body += "<div class=label>바꾼 인용</div>" + sources(change["to"], "new")
            else:
                body += "<div class=label>인용한 근거</div>" + sources(row["sources"])
            parts.append(f"<div class=card>{body}</div>")

    page = f"""<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>{esc(name)} 검토</title>
<style>
body {{ font: 14px/1.6 -apple-system, sans-serif; max-width: 980px; margin: 24px auto; padding: 0 16px; color: #1c1c1e; }}
h1 {{ font-size: 20px; }} h2 {{ font-size: 16px; margin: 32px 0 4px; border-top: 2px solid #1c1c1e; padding-top: 12px; }}
.note {{ color: #555; margin: 0 0 12px; }}
.card {{ border: 1px solid #d8d8dc; border-radius: 6px; padding: 10px 12px; margin: 8px 0; }}
.meta {{ font-weight: 600; }} .crit, .label, .diff {{ color: #555; font-size: 12px; }}
.before {{ color: #666; margin-top: 6px; }} .after {{ margin: 6px 0; }}
mark {{ background: #ffe08a; padding: 0 2px; }}
.src {{ font-size: 12px; background: #f4f4f6; border-radius: 4px; padding: 6px 8px; margin: 4px 0; }}
.src.old {{ opacity: .6; }} .src.new {{ background: #fdecea; }}
</style></head><body>
<h1>{esc(name)}: 오류 심은 세트 검토 ({len(rows)}문장)</h1>
<p>노란색이 바꾸거나 덧붙인 곳이다. 확인할 것: 오류로 심은 문장이 정말 오류인가, 대조 문장이 정말 괜찮은 문장인가,
문장 하나에 오류가 하나뿐인가. 이상한 문장은 번호(seed-000)로 알려 주면 된다.</p>
{''.join(parts)}
</body></html>"""
    SCREEN.mkdir(parents=True, exist_ok=True)
    screen = SCREEN / f"{name}.html"
    screen.write_text(page, encoding="utf-8")
    print(f"검토 화면: {screen.relative_to(EVAL_DIR)}")


if __name__ == "__main__":
    main()
