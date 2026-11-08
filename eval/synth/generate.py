"""합성 검진 결과지를 만든다.

사용법 (eval/ 폴더에서):
    .venv/bin/python -m synth.generate --out ../data/synthetic/extraction/v1

결과물 (보고서마다):
    {id}.txt            텍스트 결과지
    {id}.pdf            PDF 결과지 (gitignore 대상, 같은 시드로 다시 만든다)
    {id}.expected.json  정답 JSON (CheckupReport 형식)
    manifest.jsonl      보고서별 메타 (템플릿, 방해 요소, 구간 분포)
"""
from __future__ import annotations

import argparse
import json
import os
import random
from collections import Counter
from pathlib import Path

from . import render
from .sampling import load_spec, sample_items, sample_subject
from .templates import TEMPLATES

DEFAULT_FONT = "/System/Library/Fonts/Supplemental/AppleGothic.ttf"


def generate(out: Path, per_template: int, seed: int, font: Path, pdf: bool) -> list[dict]:
    spec = load_spec()
    labels = spec["judgment_labels"]
    out.mkdir(parents=True, exist_ok=True)
    manifest = []

    for template_id, template in TEMPLATES.items():
        for i in range(1, per_template + 1):
            report_id = f"{template_id}-{i:03d}"
            rng = random.Random(f"{seed}:{report_id}")  # 보고서별 시드: 개수를 바꿔도 기존 보고서는 그대로
            subject = sample_subject(rng)
            comprehensive = template_id == "sum"
            items = sample_items(
                rng, spec, subject,
                include_lipids=comprehensive or rng.random() < 0.5,  # 지질 검사는 4년 주기
                include_senses=template_id != "sum" and rng.random() < 0.8,
                n_other=rng.randint(2, 4) if comprehensive else 0,
            )
            blocks, expected, meta = template(rng, subject, items, labels)
            text = render.to_text(blocks)
            _check_consistency(report_id, text, expected)

            (out / f"{report_id}.txt").write_text(text, encoding="utf-8")
            (out / f"{report_id}.expected.json").write_text(
                json.dumps(expected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            if pdf:
                render.to_pdf(blocks, out / f"{report_id}.pdf", font)

            manifest.append({
                "id": report_id,
                "template": template_id,
                "examType": expected["examType"],
                "itemCount": len(expected["items"]),
                "otherCount": sum(1 for it in expected["items"] if it["code"] == "OTHER"),
                "bands": dict(Counter(m.band or "none" for m in items)),
                **meta,
            })

    with (out / "manifest.jsonl").open("w", encoding="utf-8") as f:
        for row in manifest:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    return manifest


def _check_consistency(report_id: str, text: str, expected: dict) -> None:
    """정답 JSON의 문자열이 모두 문서에 찍혀 있는지 확인한다. 어긋나면 생성을 멈춘다."""
    missing = [f"{item['code']}.{key}={item[key]!r}"
               for item in expected["items"]
               for key in ("rawName", "value", "unit", "referenceRaw", "centerJudgment")
               if item[key] and item[key] not in text]
    if missing:
        raise ValueError(f"{report_id}: 정답 값이 문서에 없음 → {missing}")


def main() -> None:
    parser = argparse.ArgumentParser(description="합성 검진 결과지 생성기")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--per-template", type=int, default=10)
    parser.add_argument("--seed", type=int, default=20261108)
    parser.add_argument("--font", type=Path, default=Path(os.environ.get("SYNTH_FONT", DEFAULT_FONT)),
                        help="한글 TrueType 폰트 경로 (환경변수 SYNTH_FONT로도 지정)")
    parser.add_argument("--no-pdf", action="store_true", help="텍스트와 정답만 만든다")
    args = parser.parse_args()

    manifest = generate(args.out, args.per_template, args.seed, args.font, pdf=not args.no_pdf)
    items = sum(r["itemCount"] for r in manifest)
    bands = Counter()
    for r in manifest:
        bands.update(r["bands"])
    print(f"보고서 {len(manifest)}개, 항목 {items}개 → {args.out}")
    print("구간 분포:", dict(bands))


if __name__ == "__main__":
    main()
