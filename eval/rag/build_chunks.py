"""해설 근거 문서를 검색 단위(청크)로 나눈다 (ADR 0007 2·3절). 모델을 부르지 않는다.

입력: data/guidelines/sources.yaml, raw/(내려받은 원문, gitignore), curated/(손으로 정리한 고시 표, 커밋)
출력: data/guidelines/chunks/chunks.jsonl (gitignore. 포털 글은 공공누리 제4유형이라 원문을 저장소에 올리지 않는다)

- 고시 별표 4: curated/mohw-2026-6-annex4.jsonl의 행을 그대로 청크로 쓴다 (판정 구분 5행 + 판정기준 16행)
- 국가건강정보포털: 절(<div class="contents-Div"> 안의 <h3>) 단위로 나누고, 긴 절은 문단 경계에서 자른다.
  문장은 바꾸지 않는다(변경금지). 약물 치료·참고문헌·지원체계(복약 Q&A) 절은 넣지 않는다(가드레일 원칙 5)
- 문서별 예외(sources.yaml의 chunking, 2026-11-12 사용자 결정): include_sections는 기본 규칙이 빼는 절을 넣고,
  cut_from은 그 절에서 정해 둔 문장으로 시작하는 문단부터 절 끝까지 뺀다. 둘 다 원문을 바꾸지 않고 빼거나 넣기만 한다
- --drop-drug-paragraphs (ADR 0008 4절 4번의 3): 남은 절에서도 약 이름·약 계열·치료로 쓰는 인슐린·복용 지시가 든
  문단은 통째로 뺀다. 공공누리 제4유형(변경금지)이라 문장에서 약 이름만 지울 수는 없다. 문단을 빼는 것은 절을 빼는 것과 같은 원칙이다.
  청크로 묶은 **뒤에** 각 청크 안에서 문단을 뺀다. 그래야 청크 id가 기준선과 같은 자리(같은 내용이나 그 일부)를 가리킨다.
  먼저 빼고 묶으면 뒤 문단이 당겨져 20개 id가 다른 내용을 가리켰다 (Codex 리뷰, 2026-11-11). 문단이 다 빠진 청크는 없앤다.
  짝비교 전까지는 기본값이 끔이다 (기준선 청크를 다시 만들 수 있게).

사용법 (eval/ 폴더에서): .venv/bin/python -m rag.build_chunks [--drop-drug-paragraphs]
"""
from __future__ import annotations

import argparse
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
    # 2026-11-12 추가 (ADR 0010 순서 5, 후속 질문 경로). 판정 해설의 조회표에는 넣지 않았다 (그 경로는 닫았다)
    "kdca-5457": ["SERUM_CREATININE", "EGFR", "URINE_PROTEIN"],
    "kdca-6673": ["AST", "ALT", "GAMMA_GTP"],
    "kdca-5310": ["AST", "ALT", "GAMMA_GTP"],
    "kdca-1104": ["HEMOGLOBIN"],
    "kdca-6694": ["BMI", "WAIST_CIRCUMFERENCE"],
}
# 넣지 않는 절: 약물·치료 지시(가드레일 원칙 5)와 해설 근거로 쓸모없는 것
EXCLUDED_SECTIONS = re.compile(r"^(참고문헌|연관 주제어|지원체계|치료|치료-약물 치료|자가 관리|대상별 맞춤 정보|역학 및 통계)$")
SECTION_SLUGS = {"요약문": "summary", "개요": "overview", "개요-정의": "definition", "개요-원인": "cause",
                 "개요-경과 및 예후": "prognosis", "개요-병태생리": "pathophysiology", "개요-종류": "types",
                 "증상": "symptoms", "진단 및 검사": "diagnosis", "치료-비약물 치료": "lifestyle-treatment",
                 "정기 진찰": "checkup", "합병증": "complications", "위험요인 및 예방": "prevention",
                 "생활습관 관리": "lifestyle", "치료": "treatment", "건강에 미치는 영향": "health-effects",
                 "실천 방법-일반적 실천 방법": "practice-general", "실천 방법-맞춤형 실천 방법": "practice-tailored",
                 "도움 및 지지": "support"}
# 약 이름·약 계열, 치료로 쓰는 인슐린, 복용 지시 (2026-11-11 포털 청크 전수 확인으로 만든 목록).
# "인슐린" 단독은 몸의 호르몬 설명(분비, 저항성)이라 막지 않는다. 일반 명사 "약물"도 막지 않는다
DRUG_TERMS = re.compile(r"(이뇨제|차단제|억제제|스타틴|항혈소판제|항응고제|항우울제|항경련제|호르몬제|메트포르민|설포닐|치료제|약제"
                        r"|인슐린\s*(주사|투여|치료|요법|펌프)|(약|약물)[을를]?\s*(\S+\s+)?(복용|투여|처방|중단|변경|증량|감량))")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--drop-drug-paragraphs", action="store_true")
    args = parser.parse_args()
    sources = {s["id"]: s for s in yaml.safe_load((GUIDELINES / "sources.yaml").read_text(encoding="utf-8"))}
    chunks = annex_chunks(sources["mohw-2026-6-annex4"])
    dropped: list[str] = []
    for doc_id in PORTAL_ITEM_CODES:
        chunks += portal_chunks(sources[doc_id], args.drop_drug_paragraphs, dropped)
    if args.drop_drug_paragraphs:
        leaked = [c["id"] for c in chunks if c["metadata"]["doc_id"] != "mohw-2026-6-annex4" and DRUG_TERMS.search(c["text"])]
        assert not leaked, f"약물 용어가 남은 청크: {leaked}"
        print(f"약물 문단 {len(dropped)}개를 뺐다: {dict((d, dropped.count(d)) for d in sorted(set(dropped)))}")
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


def drop_drug_paragraphs(paragraphs: list[str]) -> tuple[list[str], int]:
    kept = [p for p in paragraphs if not DRUG_TERMS.search(p)]
    return kept, len(paragraphs) - len(kept)


def portal_chunks(source: dict, drop_drugs: bool = False, dropped: list[str] | None = None) -> list[dict]:
    page = (GUIDELINES / source["file"]).read_text(encoding="utf-8")
    rules = source.get("chunking") or {}
    included, cut_from = set(rules.get("include_sections") or []), rules.get("cut_from") or {}
    chunks = []
    for block in re.split(r'<div[^>]*class="contents-Div"[^>]*>', page)[1:]:
        block = block.split('<div class="contents-Div"')[0]
        heading = re.search(r"<h3[^>]*>(.*?)</h3>", block, re.S)
        if not heading:
            continue
        section = _text(heading.group(1))
        if EXCLUDED_SECTIONS.match(section) and section not in included:
            continue
        paragraphs = [p for p in (_text(x) for x in re.split(r"</p>|<br\s*/?>|</li>|</tr>", block[heading.end():])) if p]
        if section in cut_from:
            starts = [i for i, p in enumerate(paragraphs) if p.startswith(cut_from[section])]
            if not starts:  # 원문이 바뀌어 표시 문장을 못 찾으면, 빼야 할 내용이 섞일 수 있으므로 멈춘다
                raise SystemExit(f"{source['id']} {section}: 잘라 낼 시작 문장을 못 찾았다: {cut_from[section]}")
            paragraphs = paragraphs[:starts[0]]
        slug = SECTION_SLUGS.get(section) or re.sub(r"\W+", "-", section)
        for n, group in enumerate(_pack_groups(paragraphs), start=1):
            if drop_drugs:
                group, removed = drop_drug_paragraphs(group)
                if dropped is not None:
                    dropped.extend([f"{source['id']} {section}"] * removed)
                if not group:
                    continue
            chunks.append({"id": f"{source['id']}-{slug}-{n}",
                           "text": f"[{source['title']} | {section}] {' '.join(group)}",
                           "metadata": _metadata(source, section, PORTAL_ITEM_CODES[source["id"]])})
    return chunks


def _text(fragment: str) -> str:
    fragment = re.sub(r"<script.*?</script>|<style.*?</style>", " ", fragment, flags=re.S)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).replace("​", "").strip()


def _pack_groups(paragraphs: list[str]) -> list[list[str]]:
    """문단을 바꾸지 않고, 이어 붙였을 때 MAX_CHARS 안팎이 되도록 묶는다 (청크 하나 = 문단 묶음 하나)"""
    groups, current, length = [], [], 0
    for p in paragraphs:
        if current and length + len(p) + 1 > MAX_CHARS:
            groups.append(current)
            current, length = [p], len(p)
        else:
            length = length + len(p) + (1 if current else 0)
            current.append(p)
    if current:
        groups.append(current)
    return groups


if __name__ == "__main__":
    main()
