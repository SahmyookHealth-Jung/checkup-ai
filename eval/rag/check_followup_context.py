"""후속 질문 문맥의 순위 방식(벡터 / BM25)을 바꿀 때의 위험 점검 (ADR 0011, 2026-11-12 사용자 요청). LLM을 부르지 않는다.

BM25가 개발 몫에서 정답 절을 더 잘 담았지만(1단계), 나중에 이상한 답변으로 이어질 경우가 없는지 본다.
기본은 개발 몫만 본다 (설계 판단은 개발 몫으로 한다).

점검
1. 항목 고름 / 안 고름 두 조건 모두에서 정답 절이 모두 담긴 문항 수 (답 가능)
2. 바꾸면 근거를 잃는 문항: 벡터로는 정답 절이 담겼는데 BM25로는 빠지는 문항 (→ 나중에 잘못된 "근거 없음")
3. 질문과 글자가 하나도 겹치지 않아 BM25 점수가 모두 0인 문항 (이때는 벡터 순서를 그대로 쓴다)
4. 답하면 안 되는 문항(답 불가·거절·악성)의 문맥에 질문의 핵심 말이 든 엉뚱한 절이 실리는가
   (예: "갑상선"을 묻는데 이상지질혈증 원인 절의 "갑상선기능저하증"이 실림 → 주제 밖 질문에 그럴듯한 답)
5. 문맥 안 약 언급 절의 수 (build_chunks.DRUG_TERMS)

사용법 (eval/ 폴더에서, 앱이 떠 있고 청크를 적재한 상태):
    .venv/bin/python -m rag.check_followup_context --budget 8000 [--split dev]
"""
from __future__ import annotations

import argparse
import json
import re

from rag.build_chunks import DRUG_TERMS
from rag.followup_evidence import GOLD
from rag.run_followup_context_eval import FROZEN, HUGE, bigrams, bm25_rank, pack, post

STOP = set("는은이가을를에서의로도만요까나고서면게지야어해줘거")  # 핵심 말을 고를 때 뺄 글자
# 주제를 가르지 못하는 흔한 말 (핵심 말에서 뺀다)
GENERIC = {"어떤", "무슨", "얼마", "언제", "나중", "다음", "결과", "검진", "검사", "수치", "정상", "질환", "병원", "결과지",
           "항목", "해당", "진단", "확실", "이거", "이건", "제가", "저는", "지금", "앞에", "오늘", "부터"}


def bm25_zero(query: str, units: list[dict]) -> bool:
    terms = set(bigrams(query))
    return not any(terms & set(bigrams(u["text"])) for u in units)


def key_words(question: str) -> list[str]:
    """질문의 핵심 말 (두 글자 이상의 낱말에서 조사를 뗀 것). 엉뚱한 절 점검에만 쓴다"""
    words = re.findall(r"[가-힣A-Za-z0-9]+", question)
    out = []
    for w in words:
        while len(w) > 2 and w[-1] in STOP:
            w = w[:-1]
        if len(w) >= 2 and w[-1] not in "어아나지요다" and w not in GENERIC:
            out.append(w)
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--budget", type=int, default=8000)
    parser.add_argument("--split", default="dev")
    args = parser.parse_args()
    gold = [json.loads(line) for line in GOLD.read_text(encoding="utf-8").splitlines() if line.strip()]
    gold = [g for g in gold if g["split"] == args.split]
    lost, zero, offtopic, drugs = [], [], [], {"vector": 0, "bm25": 0}
    full = {f"{m}-{r}": 0 for m in ("selected", "inferred") for r in ("vector", "bm25")}
    n_answerable = 0
    for g in gold:
        report = json.loads((FROZEN / f"{g['report']}.json").read_text(encoding="utf-8"))["report"] if g.get("report") else None
        expected = set(g.get("expectedSections") or [])
        for mode in ("selected", "inferred"):
            body = {"report": report, "question": g["question"]}
            if mode == "selected" and g.get("itemCodes"):
                body["itemCodes"] = g["itemCodes"]
            vector = post(args.api, body | {"budgetChars": args.budget, "ranking": "VECTOR"})
            whole = post(args.api, body | {"budgetChars": HUGE, "ranking": "VECTOR"})
            ranked = pack(bm25_rank(whole["query"], whole["rankedUnits"]), args.budget)
            contexts = {"vector": vector["fixedUnits"] + vector["rankedUnits"], "bm25": whole["fixedUnits"] + ranked}
            ids = {k: {u["id"] for u in v} for k, v in contexts.items()}
            if mode == "inferred":
                for k, units in contexts.items():
                    drugs[k] += sum(1 for u in units if DRUG_TERMS.search(u["text"]))
                if bm25_zero(whole["query"], whole["rankedUnits"]):
                    zero.append(g["id"])
            if g["category"] == "answerable":
                if mode == "selected":
                    n_answerable += 1
                for k in ("vector", "bm25"):
                    full[f"{mode}-{k}"] += expected <= ids[k]
                if expected <= ids["vector"] and not expected <= ids["bm25"]:
                    lost.append(f"{g['id']} ({mode}) {g['question']} → 빠진 절 {sorted(expected - ids['bm25'])}")
            elif mode == "inferred":
                words = key_words(g["question"])
                for k in ("vector", "bm25"):
                    hits = [u["id"] for u in contexts[k] if any(w in u["text"] for w in words)]
                    if hits:
                        offtopic.append(f"{g['id']} [{g['category']}] {k}: 핵심 말 {words} → {hits[:4]} | {g['question']}")
    print(f"개발 몫 답 가능 {n_answerable}문항, 예산 {args.budget}자, 정답 절이 모두 담긴 문항 수:")
    for k, v in full.items():
        print(f"   {k:<18} {v}/{n_answerable}")
    print("\n2. BM25로 바꾸면 근거를 잃는 문항:", "없음" if not lost else "")
    print("\n".join("   " + x for x in lost))
    print("\n3. 질문과 글자가 하나도 겹치지 않는 문항:", zero or "없음")
    print("\n4. 답하면 안 되는 문항의 문맥에 질문의 핵심 말이 든 절:")
    print("\n".join("   " + x for x in offtopic) or "   없음")
    print("\n5. 문맥 안 약 언급 절 수 (항목 안 고름, 전체 문항 합):", drugs)


if __name__ == "__main__":
    main()
