"""후속 질문 정답셋의 "찾아야 할 청크" 라벨링 (ADR 0010 순서 5 뒤).

질문은 근거 문서를 보지 않고 먼저 썼고(followup_gold.py), 문서를 넣은 뒤 여기서 근거 청크를 붙인다 (사용자 결정, 2026-11-12).
- 단위는 청크다. 다음 단계의 검색 평가가 "찾아야 할 청크를 상위 k개 안에 찾았나"를 잰다.
  청크 규칙을 바꾸면 라벨을 다시 맞춰야 하므로 라벨에 청크 파일 해시를 남긴다.
- 답 가능: 근거 청크를 하나 이상 고른다. 근거가 정말 없으면 "근거 없음"을 고르고, 반영할 때 답 불가로 바꾼다.
- 답 불가: 근거가 정말 없는지 확인한다. 근거를 찾으면 고르고, 반영할 때 답 가능으로 바꾼다.
- 거절·악성은 라벨링하지 않는다 (검색 결과와 상관없이 답하지 않아야 한다).

명령 (eval/ 폴더에서)
    .venv/bin/python -m rag.followup_evidence form       # → eval/.cache/labeling/followup-evidence.html (근거 원문이 있어 커밋 안 함)
    .venv/bin/python -m rag.followup_evidence apply <내려받은 JSON>   # 정답셋(followup-v1.jsonl)에 expectedChunks를 채운다
"""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime
from pathlib import Path

from rag.followup_gold import CATEGORIES, ITEM_TOPICS, OUT, SCREEN, TOPICS, report_contexts
from run_record import EVAL_DIR, ROOT

CHUNKS = ROOT / "data" / "guidelines" / "chunks" / "chunks.jsonl"
GOLD = OUT / "followup-v1.jsonl"
MANIFEST = OUT / "followup-v1.manifest.json"
LABELED = ("answerable", "unanswerable")
CRITERIA_DOC = "mohw-2026-6-annex4"
# 주제 → 근거 문서. 후보 청크를 좁히는 데만 쓴다. 화면에서 전체 문서로 넓혀 볼 수 있다
TOPIC_DOCS = {
    "criteria": [CRITERIA_DOC], "hypertension": ["kdca-6765"], "diabetes": ["kdca-5305"],
    "dyslipidemia": ["kdca-6715"], "liver": ["kdca-6673", "kdca-5310"], "kidney": ["kdca-5457"],
    "anemia": ["kdca-1104"], "obesity": ["kdca-6694"],
}


def chunks_sha256() -> str:
    return hashlib.sha256(CHUNKS.read_bytes()).hexdigest()[:16]


def gold_rows() -> list[dict]:
    return [json.loads(line) for line in GOLD.read_text(encoding="utf-8").splitlines() if line.strip()]


def candidate_docs(row: dict, flagged: list[dict]) -> list[str]:
    """질문의 주제 → 질문 항목들의 주제 → 결과지의 정상B·질환의심 항목들의 주제 순으로 문서를 고른다.
    판정 기준(고시)은 늘 넣는다. 아무것도 없으면 빈 목록(= 전체 문서)"""
    codes = row.get("itemCodes") or [i["code"] for i in flagged]
    topics = [row["topic"]] if row.get("topic") else sorted({ITEM_TOPICS[c] for c in codes if c in ITEM_TOPICS})
    docs = [doc for t in topics for doc in TOPIC_DOCS.get(t, [])]
    return [] if not docs else list(dict.fromkeys([*docs, CRITERIA_DOC]))


def build_form() -> Path:
    chunks = [json.loads(line) for line in CHUNKS.read_text(encoding="utf-8").splitlines() if line.strip()]
    contexts = {c["key"]: c for c in report_contexts()}
    questions = []
    for r in gold_rows():
        if r["category"] not in LABELED:
            continue
        ctx = contexts.get(r.get("report") or "")
        flagged = [i for i in (ctx["items"] if ctx else []) if i["judgment"] in ("SUSPECT", "NORMAL_B")]
        questions.append({**r, "flagged": flagged, "candidateDocs": candidate_docs(r, flagged)})
    docs = {}
    for c in chunks:
        m = c["metadata"]
        docs.setdefault(m["doc_id"], m.get("title"))
    data = {"set": "followup-v1", "chunksSha256": chunks_sha256(), "categories": CATEGORIES, "topics": TOPICS,
            "docs": docs, "questions": questions,
            "chunks": [{"id": c["id"], "doc": c["metadata"]["doc_id"], "section": c["metadata"].get("section"),
                        "text": c["text"]} for c in chunks]}
    html = (Path(__file__).parent / "followup_evidence.html").read_text(encoding="utf-8")
    html = html.replace("__DATA__", json.dumps(data, ensure_ascii=False).replace("</", "<\\/"))
    SCREEN.mkdir(parents=True, exist_ok=True)
    screen = SCREEN / "followup-evidence.html"
    screen.write_text(html, encoding="utf-8")
    return screen


def apply(labels_file: Path) -> None:
    doc = json.loads(labels_file.read_text(encoding="utf-8"))
    if doc.get("chunksSha256") != chunks_sha256():
        raise SystemExit(f"청크 파일이 라벨링 때와 다르다 ({doc.get('chunksSha256')} → {chunks_sha256()}). 라벨을 다시 맞춘다")
    labels = doc["labels"]
    known = {c["id"] for c in (json.loads(line) for line in CHUNKS.read_text(encoding="utf-8").splitlines() if line.strip())}
    rows = gold_rows()
    missing = [r["id"] for r in rows if r["category"] in LABELED and r["id"] not in labels]
    if missing:
        raise SystemExit(f"라벨이 없는 문항 {len(missing)}개: {', '.join(missing)}")
    changed = []
    for r in rows:
        r.pop("expectedSections", None)  # 처음 고정할 때 이름. 청크 단위로 정해 expectedChunks로 바꿨다
        label = labels.get(r["id"])
        if r["category"] not in LABELED or label is None:
            r["expectedChunks"] = None
            continue
        chunk_ids = [c for c in label.get("chunks") or [] if c]
        unknown = [c for c in chunk_ids if c not in known]
        if unknown:
            raise SystemExit(f"{r['id']}: 모르는 청크 {unknown}")
        if label.get("noEvidence") and chunk_ids:
            raise SystemExit(f"{r['id']}: 근거 없음과 청크를 함께 골랐다")
        if not label.get("noEvidence") and not chunk_ids:
            raise SystemExit(f"{r['id']}: 청크를 고르거나 근거 없음을 골라야 한다")
        new_category = "unanswerable" if label.get("noEvidence") else "answerable"
        if new_category != r["category"]:
            changed.append(f"{r['id']} {r['category']} → {new_category}: {r['question']}")
            r["category"] = new_category
            r["revisedBy"] = "user"
            r["revision"] = ("근거 문서에 답이 없음 (청크 라벨링)" if new_category == "unanswerable"
                             else "근거 문서에 답이 있음 (청크 라벨링)")
            if new_category == "unanswerable":
                r["topic"] = None
        r["expectedChunks"] = chunk_ids or None
        if label.get("note"):
            r["evidenceNote"] = label["note"]
    GOLD.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["evidence"] = {"labeledAt": doc.get("exportedAt"), "appliedAt": datetime.now().isoformat(timespec="seconds"),
                            "chunksSha256": chunks_sha256(), "unit": "chunk", "categoryChanges": changed,
                            "rule": "청크 규칙을 바꾸면 라벨을 다시 맞춘다"}
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    labeled = [r for r in rows if r["category"] == "answerable"]
    print(f"반영: 답 가능 {len(labeled)}문항, 평균 근거 청크 "
          f"{sum(len(r['expectedChunks'] or []) for r in labeled) / max(len(labeled), 1):.1f}개")
    print("범주가 바뀐 문항:\n" + ("\n".join(changed) if changed else "없음"))


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("form")
    p_apply = sub.add_parser("apply")
    p_apply.add_argument("labels", type=Path)
    args = parser.parse_args()
    if args.command == "form":
        print(f"라벨링 화면: {build_form()}")
    else:
        apply(args.labels)


if __name__ == "__main__":
    main()
