"""품질 화면 데이터 (ADR 0012 1절). 커밋된 평가 기록을 정적 JSON 하나로 묶는다. 모델 호출 없음.

앱은 실행 중에 eval/ 파일을 읽지 않는다. 평가를 새로 돌리면 이 스크립트를 다시 돌리고 결과 JSON을 커밋한다.
- highlights: README 결과 표와 같은 대표 수치 (값은 ADR 결과에서 옮겼다. 고치면 README와 함께 고친다)
- followup: 후속 질문 평가 기록에서 바로 계산한 행동 표 (시험, 재확인, 충실도)
- runs: eval/results/index.jsonl 전체 (dirty 기록 포함, 화면에서 표시)

사용법 (eval/ 폴더에서):
    .venv/bin/python build_dashboard_data.py
"""
from __future__ import annotations

import json
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
RESULTS = EVAL_DIR / "results"
OUT = EVAL_DIR.parent / "backend" / "app" / "src" / "main" / "resources" / "static" / "data" / "quality.json"

ADR = "https://github.com/SahmyookHealth-Jung/checkup-ai/blob/main/docs/adr/"

# status: good(목표 달성) / limit(조건이 붙음) / bad(목표 미달). 화면에서 색과 함께 글자로도 보인다
HIGHLIGHTS = [
    {"area": "추출", "title": "결과지 수치 추출", "value": "F1 0.995", "detail": "Haiku 5.5 + 원문 대조 검증. 로컬 qwen3:4b는 0.925. 보고서당 5초, $0.0012",
     "set": "처음 보는 양식 3종 30개 (두 양식은 로컬 모델 결과를 보고 검증 규칙을 고침)", "status": "limit", "adr": "0005-추출-모델-선택.md"},
    {"area": "개인정보", "title": "외부 전송 전 식별정보 제거", "value": "0/90 남음", "detail": "보내기 전에 이름·생년월일 등을 지운다",
     "set": "합성 결과지 60개", "status": "good", "adr": "0006-LLM-호출-기록과-식별정보-제거.md"},
    {"area": "추출", "title": "값·단위 표기 변형", "value": "조용히 지나감 5 → 0", "detail": "틀린 경보도 5 → 0 (146항목). 같은 세트를 보고 고쳐서 일반화 수치가 아니다",
     "set": "heldout-v4 10개 (본 세트)", "status": "limit", "adr": "0004-추출-검증-원칙.md"},
    {"area": "해설", "title": "위험 수치 진료 권고", "value": "73/73", "detail": "잘못된 권고 0 (95% 하한 약 96%). 같은 규칙을 쓰는 두 구현의 일치",
     "set": "처음 보는 양식 30개, 2회", "status": "good", "adr": "0010-해설-두-경로-분리와-후속-질문.md"},
    {"area": "해설", "title": "판정 해설: 벡터 검색 → 항목 코드 조회", "value": "비용 −27%", "detail": "LLM 문장 437 → 176, LLM 지연 6.0 → 3.3~3.6초",
     "set": "처음 보는 양식 30개, 2회", "status": "good", "adr": "0010-해설-두-경로-분리와-후속-질문.md"},
    {"area": "채점기", "title": "충실도 채점기 검증", "value": "심은 오류 57/57", "detail": "판정 구간 37/37, 치료·약 20/20. 사람과의 일치(κ)는 뒷받침 축 0.05~0.15로 낮다",
     "set": "오류 심은 141문장, 보정 50문장", "status": "limit", "adr": "0009-의미-충실도-채점기-설계.md"},
    {"area": "후속 질문", "title": "문맥: 항목 범위 + 글자 2-gram BM25", "value": "개발 7 → 10/10", "detail": "정답 근거가 문맥에 다 실린 문항 (벡터 대비). 시험 13/18",
     "set": "질문 60개 (개발 20 / 시험 40)", "status": "good", "adr": "0011-후속-질문-경로-설계.md"},
    {"area": "후속 질문", "title": "답하면 안 되는 질문에 답함", "value": "시험 2/22", "detail": "둘 다 진단 질문 (followup-v3, 시험은 다시 재지 않음)",
     "set": "시험 40문항 1회", "status": "bad", "adr": "0011-후속-질문-경로-설계.md"},
    {"area": "후속 질문", "title": "진단 거절: 질문 분류 + 답 검사", "value": "새 문항 1/36", "detail": "진단 요청에 답한 건수. 같은 실행에서 예전 방식(규칙 또는 LLM)이면 22/36. 경계 질문을 진단 거절 0/24",
     "set": "구현 전에 별도 세션이 쓴 30문항 × 2방식, 1회", "status": "limit", "adr": "0014-후속-질문-진단-거절-보강.md"},
    {"area": "후속 질문", "title": "정답 근거를 인용해 답함", "value": "시험 8~10/18", "detail": "통과 기준 80% 미달. 답 문장 충실도 22/36 뒷받침, 판정 구간 틀림·치료 지시 0",
     "set": "시험 40문항", "status": "bad", "adr": "0011-후속-질문-경로-설계.md"},
    {"area": "통계", "title": "관리자 통계 질문 (Text-to-SQL)", "value": "32/34 일치", "detail": "v2(구조 검사·서버가 센 인원으로 숨김) 기준. 시험 몫을 v1에서 본 뒤라 깨끗한 수치는 v1의 27/34. 막을 요청 실행 0, 공격 6문항에서 숨김 없이 나간 작은 값 0",
     "set": "시험 40문항 (Claude 작성), 공격 6문항, 공공데이터 100만 행", "status": "limit", "adr": "0013-Text-to-SQL-통계-어시스턴트-설계.md"},
]

FOLLOWUP_RUNS = {
    "dev": "20261112-2058_followup-answer_dev-v3",
    "test": "20261112-2101_followup-answer_test-v3",
    "recheck": "20261112-2118_followup-answer_recheck-v3-rule",
    "recheck2": "20261113-1318_followup-answer_recheck2-v4-intent",
    "judge": "20261112-2102_faithfulness_20261112-2101_followup-answer_test-v3_off_followup-test-v3",
}


def index_rows() -> list[dict]:
    return [json.loads(line) for line in (RESULTS / "index.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]


def record(run_id: str, rows: list[dict]) -> dict:
    row = next((r for r in rows if r["runId"] == run_id), None)
    if row is None:
        raise SystemExit(f"index에 없는 기록: {run_id}")
    return json.loads((EVAL_DIR / row["path"]).read_text(encoding="utf-8"))


def followup(rows: list[dict]) -> dict:
    out = {}
    for name in ("dev", "test", "recheck", "recheck2"):
        rec = record(FOLLOWUP_RUNS[name], rows)
        out[name] = {"runId": rec["runId"], "commit": rec["git"]["commit"], "dirty": rec["git"]["dirty"],
                     "dataset": rec["dataset"], "modes": {
                         mode: {k: rec["summary"][mode]["all"][k] for k in
                                ("answeredButShouldNot", "answerableGrounded", "answerableAnswered", "n", "behavior",
                                 "refuseReasonMatch", "forbiddenHits", "avgLlmMs")} | {"pass": rec["summary"][mode]["pass"]}
                         for mode in rec["summary"]}}
    judge = record(FOLLOWUP_RUNS["judge"], rows)
    out["faithfulness"] = {"runId": judge["runId"], "commit": judge["git"]["commit"],
                           **{k: judge["summary"][k] for k in ("support", "bandWrong", "treatmentPresent", "judged", "costUsd")}}
    return out


def main() -> None:
    rows = index_rows()
    data = {
        "adrBase": ADR,
        "highlights": HIGHLIGHTS,
        "followup": followup(rows),
        "runs": [{k: r.get(k) for k in ("runId", "kind", "label", "createdAt", "commit", "dirty", "dataset", "headline")}
                 for r in rows],
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"저장: {OUT.relative_to(EVAL_DIR.parent)} (기록 {len(rows)}개)")


if __name__ == "__main__":
    main()
