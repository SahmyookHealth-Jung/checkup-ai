"""평가 실행 기록의 공통 형식 (ADR 0008 4절 2번, 2026-11-10 결정: JSON 파일 + 목록).

- 실행마다 eval/results/<kind 폴더>/<runId>.json 하나를 쓴다. 모든 파일은 같은 겉모양(envelope)을 갖는다.
- eval/results/index.jsonl에 실행마다 한 줄(머리 지표 + 파일 경로)을 덧붙인다.
  6단계 품질 대시보드는 이 목록과 파일을 읽는 조회 API만 더하면 된다.
- 커밋을 함께 적는다. 커밋하지 않은 변경이 있으면 dirty=true로 남긴다 (처음 보는 데이터의 수치로 쓰지 않는다).
- 저장소에 올리면 안 되는 원문(포털 글, 공공누리 제4유형)은 넣지 않는다. 근거는 청크 id로만 남긴다.
"""
from __future__ import annotations

import json
import subprocess
from datetime import datetime
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
ROOT = EVAL_DIR.parent
RESULTS = EVAL_DIR / "results"
INDEX = RESULTS / "index.jsonl"
SCHEMA = "checkup-ai/eval-run@1"


def git_state() -> dict:
    def git(*args: str) -> str:
        return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    # 결과 폴더 자체의 변경(이번 실행이 쓰는 파일)은 dirty로 보지 않는다
    changed = [line for line in git("status", "--porcelain").splitlines() if "eval/results/" not in line]
    return {"commit": git("rev-parse", "--short", "HEAD"), "dirty": bool(changed)}


def new_run_id(kind: str, label: str) -> str:
    return f"{datetime.now():%Y%m%d-%H%M}_{kind}_{label}"


def save(run_id: str, kind: str, folder: str, *, label: str, dataset: dict, config: dict, summary: dict,
         headline: dict, details: dict | list, git: dict | None = None) -> Path:
    """실행 하나를 저장하고 목록에 한 줄을 더한다. headline은 대시보드 목록에 보일 몇 개 지표다"""
    git = git or git_state()
    record = {"schema": SCHEMA, "runId": run_id, "kind": kind, "label": label,
              "createdAt": datetime.now().isoformat(timespec="seconds"), "git": git, "dataset": dataset,
              "config": config, "summary": summary, "details": details}
    out = RESULTS / folder / f"{run_id}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    line = {"runId": run_id, "kind": kind, "label": label, "createdAt": record["createdAt"],
            "commit": git["commit"], "dirty": git["dirty"], "dataset": dataset.get("name"),
            "path": str(out.relative_to(EVAL_DIR)), "headline": headline}
    with INDEX.open("a", encoding="utf-8") as f:
        f.write(json.dumps(line, ensure_ascii=False) + "\n")
    return out
