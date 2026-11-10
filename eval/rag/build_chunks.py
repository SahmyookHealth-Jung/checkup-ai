"""해설 근거 문서를 검색 단위(청크)로 나눈다 (ADR 0007 2·3절). 모델을 부르지 않는다.

입력: data/guidelines/sources.yaml, raw/(내려받은 원문, gitignore), curated/(손으로 정리한 고시 표, 커밋)
출력: data/guidelines/chunks/chunks.jsonl (gitignore. 포털 글은 공공누리 제4유형이라 원문을 저장소에 올리지 않는다)

- 고시 별표 4: curated/mohw-2026-6-annex4.jsonl의 행을 그대로 청크로 쓴다 (판정 구분 5행 + 판정기준 16행)
- 국가건강정보포털: 절(<div class="contents-Div"> 안의 <h3>) 단위로 나누고, 긴 절은 문단 경계에서 자른다.
  문장은 바꾸지 않는다(변경금지). 약물 치료·참고문헌·지원체계(복약 Q&A) 절은 넣지 않는다(가드레일 원칙 5)

사용법 (eval/ 폴더에서): .venv/bin/python -m rag.build_chunks
"""
from __future__ import annotations

import html
import json
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
GUIDELINES = ROOT / "data" / "guidelines"
OUT = GUIDELINES / "chunks" / "chunks.jsonl"
MAX_CHARS = 700

# 포털 글이 다루는 검진 항목 (5단계 메타데이터 필터 실험용. 4단계 검색 기준선에서는 쓰지 않는다)
PORTAL_ITEM_CODES = {
    "kdca-6765": ["SYSTOLIC_BP", "DIASTOLIC_BP"],
    "kdca-5305": ["FASTING_GLUCOSE"],
    "kdca-6715": ["TOTAL_CHOLESTEROL", "HDL_CHOLESTEROL", "TRIGLYCERIDES", "LDL_CHOLESTEROL"],
}
# 넣지 않는 절: 약물·치료 지시(가드레일 원칙 5)와 해설 근거로 쓸모없는 것
EXCLUDED_SECTIONS = re.compile(r"^(참고문헌|연관 주제어|지원체계|치료|치료-약물 치료|자가 관리|대상별 맞춤 정보|역학 및 통계)$")
SECTION_SLUGS = {"요약문": "summary", "개요": "overview", "개요-정의": "definition", "개요-원인": "cause",
                 "개요-경과 및 예후": "prognosis", "개요-병태생리": "pathophysiology", "개요-종류": "types",
                 "증상": "symptoms", "진단 및 검사": "diagnosis", "치료-비약물 치료": "lifestyle-treatment",
                 "정기 진찰": "checkup", "합병증": "complications", "위험요인 및 예방": "prevention",
                 "생활습관 관리": "lifestyle"}


def main() -> None:
    sources = {s["id"]: s for s in yaml.safe_load((GUIDELINES / "sources.yaml").read_text(encoding="utf-8"))}
    chunks = annex_chunks(sources["mohw-2026-6-annex4"])
    for doc_id in PORTAL_ITEM_CODES:
        chunks += portal_chunks(sources[doc_id])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as f:
        for chunk in chunks:
            f.write(json.dumps(chunk, ensure_ascii=False) + "\n")
    by_doc: dict[str, int] = {}
    for chunk in chunks:
        by_doc[chunk["metadata"]["doc_id"]] = by_doc.get(chunk["metadata"]["doc_id"], 0) + 1
    print(f"청크 {len(chunks)}개 → {OUT}  {by_doc}")


def _metadata(source: dict, section: str, item_codes: list[str]) -> dict:
    return {"doc_id": source["id"], "title": source["title"], "publisher": source["publisher"],
            "edition": source["edition"], "url": source["url"], "license": source["license"],
            "section": section, "item_codes": item_codes}


def annex_chunks(source: dict) -> list[dict]:
    lines = (GUIDELINES / "curated" / "mohw-2026-6-annex4.jsonl").read_text(encoding="utf-8").splitlines()
    rows = [json.loads(line) for line in lines if line.strip()]
    return [{"id": r["id"], "text": r["text"], "metadata": _metadata(source, r["section"], r["item_codes"])}
            for r in rows]


def portal_chunks(source: dict) -> list[dict]:
    page = (GUIDELINES / source["file"]).read_text(encoding="utf-8")
    chunks = []
    for block in re.split(r'<div[^>]*class="contents-Div"[^>]*>', page)[1:]:
        block = block.split('<div class="contents-Div"')[0]
        heading = re.search(r"<h3[^>]*>(.*?)</h3>", block, re.S)
        if not heading:
            continue
        section = _text(heading.group(1))
        if EXCLUDED_SECTIONS.match(section):
            continue
        paragraphs = [p for p in (_text(x) for x in re.split(r"</p>|<br\s*/?>|</li>|</tr>", block[heading.end():])) if p]
        slug = SECTION_SLUGS.get(section) or re.sub(r"\W+", "-", section)
        for n, piece in enumerate(_pack(paragraphs), start=1):
            chunks.append({"id": f"{source['id']}-{slug}-{n}",
                           "text": f"[{source['title']} | {section}] {piece}",
                           "metadata": _metadata(source, section, PORTAL_ITEM_CODES[source["id"]])})
    return chunks


def _text(fragment: str) -> str:
    fragment = re.sub(r"<script.*?</script>|<style.*?</style>", " ", fragment, flags=re.S)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).replace("​", "").strip()


def _pack(paragraphs: list[str]) -> list[str]:
    """문단을 바꾸지 않고 이어 붙여, MAX_CHARS 안팎의 조각으로 묶는다"""
    pieces, current = [], ""
    for p in paragraphs:
        if current and len(current) + len(p) + 1 > MAX_CHARS:
            pieces.append(current)
            current = p
        else:
            current = f"{current} {p}".strip()
    if current:
        pieces.append(current)
    return pieces


if __name__ == "__main__":
    main()
