"""후속 질문 정답셋의 "찾아야 할 절" 라벨링 (ADR 0010 순서 5 뒤).

질문은 근거 문서를 보지 않고 먼저 썼고(followup_gold.py), 문서를 넣은 뒤 여기서 근거를 붙인다.
사용자 결정 (2026-11-12)
- 단위는 절이다. 처음에는 청크 단위로 했지만 문항마다 평균 44개 청크를 읽어야 했고, 한 절이 여러 청크로 쪼개져
  어느 조각이 근거인지 고르기 애매했다. 절 단위면 평균 12.5개다.
  - 일반 문서: "문서#절" (예: kdca-5457#치료). 고시(판정기준표·판정 구분): 행 하나가 단위다 (예: mohw-annex4-row-egfr).
  - 검색 평가는 "찾은 청크가 정답 절에 속하는가"로 잰다. 청크를 다시 만들어도 절 이름이 같으면 라벨은 그대로다.
- Claude가 문항마다 근거 절을 이유와 함께 제안하고(followup-v1.evidence-suggestions.json), 사용자가 문항마다 확인한다.
  제안에 끌려갈 수 있으므로 반영할 때 "제안 그대로 / 고침" 건수를 남긴다.
- 기준: 그 절만 읽고 질문의 핵심 답을 말할 수 있으면 고른다. 배경 설명만 되면 고르지 않는다.
- 답 가능에서 근거가 없으면 답 불가로, 답 불가에서 근거를 찾으면 답 가능으로 바꾼다. 거절·악성은 라벨링하지 않는다.

명령 (eval/ 폴더에서)
    .venv/bin/python -m rag.followup_evidence form       # → eval/.cache/labeling/followup-evidence.html (근거 원문이 있어 커밋 안 함)
    .venv/bin/python -m rag.followup_evidence apply <내려받은 JSON>   # 정답셋(followup-v1.jsonl)에 expectedSections를 채운다
    --gold followup/<이름>.jsonl   # 다른 정답셋. 제안·manifest는 같은 폴더의 <이름>.evidence-suggestions.json, <이름>.manifest.json
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

from rag.followup_gold import CATEGORIES, ITEM_TOPICS, OUT, SCREEN, TOPICS, report_contexts
from run_record import ROOT

CHUNKS = ROOT / "data" / "guidelines" / "chunks" / "chunks.jsonl"
GOLD = OUT / "followup-v1.jsonl"
LABELED = ("answerable", "unanswerable")
CRITERIA_DOC = "mohw-2026-6-annex4"
# 주제 → 근거 문서. 후보를 좁히는 데만 쓴다. 화면에서 전체 문서로 넓혀 볼 수 있다
TOPIC_DOCS = {
    "criteria": [CRITERIA_DOC], "hypertension": ["kdca-6765"], "diabetes": ["kdca-5305"],
    "dyslipidemia": ["kdca-6715"], "liver": ["kdca-6673", "kdca-5310"], "kidney": ["kdca-5457"],
    "anemia": ["kdca-1104"], "obesity": ["kdca-6694"],
}


def unit_of(chunk: dict) -> str:
    """청크가 속한 라벨 단위. 고시는 행(청크) 하나, 나머지는 문서#절"""
    m = chunk["metadata"]
    return chunk["id"] if m["doc_id"] == CRITERIA_DOC else f"{m['doc_id']}#{m['section']}"


def load_chunks() -> list[dict]:
    return [json.loads(line) for line in CHUNKS.read_text(encoding="utf-8").splitlines() if line.strip()]


def units() -> list[dict]:
    """라벨 단위 목록 (청크 파일 순서). 절의 청크 원문을 이어 붙여 보여 준다"""
    by_id: dict[str, dict] = {}
    for c in load_chunks():
        m = c["metadata"]
        unit = by_id.setdefault(unit_of(c), {"id": unit_of(c), "doc": m["doc_id"], "title": m.get("title"),
                                             "section": m.get("section"), "itemCodes": m.get("item_codes") or [],
                                             "chunks": [], "text": ""})
        body = c["text"].split("] ", 1)[1] if c["text"].startswith("[") else c["text"]
        unit["chunks"].append(c["id"])
        unit["text"] = (unit["text"] + " " + body).strip()
    return list(by_id.values())


def unit_ids_sha() -> str:
    """단위 구성(단위 id와 소속 청크)의 해시. 절 이름이 바뀌거나 단위가 생기고 없어지면 라벨을 다시 맞춘다"""
    import hashlib
    spec = json.dumps([[u["id"], u["chunks"]] for u in units()], ensure_ascii=False)
    return hashlib.sha256(spec.encode()).hexdigest()[:16]


def gold_set(gold: Path) -> tuple[str, Path, Path]:
    """정답셋 이름, manifest, Claude 제안 파일"""
    name = gold.name.removesuffix(".jsonl")
    return name, gold.with_name(f"{name}.manifest.json"), gold.with_name(f"{name}.evidence-suggestions.json")


def gold_rows(gold: Path = GOLD) -> list[dict]:
    return [json.loads(line) for line in gold.read_text(encoding="utf-8").splitlines() if line.strip()]


def candidate_units(row: dict, flagged: list[dict], all_units: list[dict]) -> list[str]:
    """주제 → 질문 항목 → 결과지 주의 항목 순으로 문서를 고르고, 고시는 그 항목들의 판정기준표 행과 판정 구분 정의만 둔다.
    아무것도 없으면 빈 목록(= 전체)"""
    codes = row.get("itemCodes") or [i["code"] for i in flagged]
    topics = [row["topic"]] if row.get("topic") else sorted({ITEM_TOPICS[c] for c in codes if c in ITEM_TOPICS})
    docs = {doc for t in topics for doc in TOPIC_DOCS.get(t, []) if doc != CRITERIA_DOC}
    if not docs and not topics and not codes:
        return []
    rows = set(codes) | {c for t in topics for c, tt in ITEM_TOPICS.items() if tt == t and not row.get("itemCodes")}
    out = []
    for u in all_units:
        if u["doc"] == CRITERIA_DOC:
            if u["section"] == "판정 구분" or set(u["itemCodes"]) & rows:
                out.append(u["id"])
        elif u["doc"] in docs:
            out.append(u["id"])
    return out


def build_form(gold: Path = GOLD) -> Path:
    name, _, suggestions_file = gold_set(gold)
    all_units = units()
    contexts = {c["key"]: c for c in report_contexts()}
    suggestions = (json.loads(suggestions_file.read_text(encoding="utf-8"))["suggestions"]
                   if suggestions_file.exists() else {})
    known = {u["id"] for u in all_units}
    questions = []
    for r in gold_rows(gold):
        if r["category"] not in LABELED:
            continue
        ctx = contexts.get(r.get("report") or "")
        flagged = [i for i in (ctx["items"] if ctx else []) if i["judgment"] in ("SUSPECT", "NORMAL_B")]
        suggestion = suggestions.get(r["id"])
        if suggestion and set(suggestion["units"]) - known:
            raise SystemExit(f"{r['id']}: 제안에 모르는 단위가 있다 {set(suggestion['units']) - known}")
        candidates = candidate_units(r, flagged, all_units)
        if suggestion and candidates:  # 제안한 단위가 후보 밖이어도 보이게 한다
            candidates += [u for u in suggestion["units"] if u not in candidates]
        questions.append({**r, "flagged": flagged, "candidates": candidates, "suggestion": suggestion})
    data = {"set": name, "unitsSha256": unit_ids_sha(), "categories": CATEGORIES, "topics": TOPICS,
            "questions": questions, "units": all_units}
    html = (Path(__file__).parent / "followup_evidence.html").read_text(encoding="utf-8")
    html = html.replace("__DATA__", json.dumps(data, ensure_ascii=False).replace("</", "<\\/"))
    SCREEN.mkdir(parents=True, exist_ok=True)
    screen = SCREEN / ("followup-evidence.html" if name == "followup-v1" else f"{name}-evidence.html")
    screen.write_text(html, encoding="utf-8")
    avg = sum(len(q["candidates"]) or len(all_units) for q in questions) / len(questions)
    print(f"문항 {len(questions)}개, 단위 {len(all_units)}개, 문항당 후보 평균 {avg:.1f}개")
    return screen


def apply(labels_file: Path, gold: Path = GOLD) -> None:
    name, manifest_file, suggestions_file = gold_set(gold)
    doc = json.loads(labels_file.read_text(encoding="utf-8"))
    if doc.get("set") != name:
        raise SystemExit(f"라벨 파일은 {doc.get('set')}용이다. --gold가 {name}이다")
    if doc.get("unitsSha256") != unit_ids_sha():
        raise SystemExit(f"절 구성이 라벨링 때와 다르다 ({doc.get('unitsSha256')} → {unit_ids_sha()}). 라벨을 다시 맞춘다")
    labels = doc["labels"]
    known = {u["id"] for u in units()}
    suggestions = json.loads(suggestions_file.read_text(encoding="utf-8"))["suggestions"]
    rows = gold_rows(gold)
    pending = [r["id"] for r in rows if r["category"] in LABELED and not (labels.get(r["id"]) or {}).get("confirmed")]
    if pending:
        raise SystemExit(f"확인하지 않은 문항 {len(pending)}개: {', '.join(pending)}")
    changed, same, edited = [], 0, []
    for r in rows:
        for old in ("expectedSections", "expectedChunks"):  # 처음 고정 때의 빈 필드를 정리한다
            r.pop(old, None)
        label = labels.get(r["id"])
        if r["category"] not in LABELED:
            r["expectedSections"] = None
            continue
        picked = [u for u in label.get("units") or [] if u]
        if set(picked) - known:
            raise SystemExit(f"{r['id']}: 모르는 단위 {set(picked) - known}")
        if bool(label.get("noEvidence")) == bool(picked):
            raise SystemExit(f"{r['id']}: 근거 절을 고르거나 근거 없음 중 하나만 골라야 한다")
        suggestion = suggestions.get(r["id"]) or {}
        if sorted(picked) == sorted(suggestion.get("units") or []) and bool(label.get("noEvidence")) == bool(suggestion.get("noEvidence")):
            same += 1
        else:
            edited.append(r["id"])
        new_category = "unanswerable" if label.get("noEvidence") else "answerable"
        if new_category != r["category"]:
            changed.append(f"{r['id']} {CATEGORIES[r['category']]} → {CATEGORIES[new_category]}: {r['question']}")
            r["category"] = new_category
            r["revisedBy"] = "user"
            r["revision"] = ("근거 문서에 답이 없음 (근거 라벨링)" if new_category == "unanswerable"
                             else "근거 문서에 답이 있음 (근거 라벨링)")
            if new_category == "unanswerable":
                r["topic"] = None
        r["expectedSections"] = picked or None
        if label.get("note"):
            r["evidenceNote"] = label["note"]
    gold.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    manifest = json.loads(manifest_file.read_text(encoding="utf-8")) if manifest_file.exists() else {"name": name}
    manifest["evidence"] = {
        "unit": "section (고시는 행)", "unitsSha256": unit_ids_sha(), "labeledAt": doc.get("exportedAt"),
        "appliedAt": datetime.now().isoformat(timespec="seconds"),
        "suggestedBy": "claude", "keptSuggestion": same, "editedSuggestion": edited,
        "categoryChanges": changed, "rule": "절 구성이 바뀌면 라벨을 다시 맞춘다"}
    manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    answerable = [r for r in rows if r["category"] == "answerable"]
    print(f"반영: 답 가능 {len(answerable)}문항, 평균 근거 절 "
          f"{sum(len(r['expectedSections'] or []) for r in answerable) / max(len(answerable), 1):.1f}개")
    print(f"Claude 제안 그대로 {same}문항, 고침 {len(edited)}문항 {edited}")
    print("범주가 바뀐 문항:\n" + ("\n".join(changed) if changed else "없음"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", type=Path, default=GOLD, help="정답셋 jsonl (기본 followup-v1)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("form")
    p_apply = sub.add_parser("apply")
    p_apply.add_argument("labels", type=Path)
    args = parser.parse_args()
    if args.command == "form":
        print(f"라벨링 화면: {build_form(args.gold.resolve())}")
    else:
        apply(args.labels, args.gold.resolve())


if __name__ == "__main__":
    main()
