"""의미 충실도 채점기 (ADR 0008 2절, ADR 0009). 문장 하나를 Sonnet 5.5로 채점한다.

- 채점 기준은 faithfulness_rubric.py에서 가져온다 (사람 라벨링 화면과 같은 정의).
- 채점기는 뒷받침하는 구절을 근거 원문 그대로 적는다. 코드가 그 구절이 근거에 실제로 있는지 확인한다.
  구절이 확인되지 않으면 "지지·부분"을 믿지 않고 최종 판정을 "미지지"로 둔다 (원래 판정도 함께 남긴다).
- 채점 기준(시스템 프롬프트)은 프롬프트 캐싱으로 재사용한다. 문장마다 바뀌는 내용은 사용자 메시지에만 둔다.
- 응답은 eval/.cache/judge/에 캐시한다. 키: 모델, 기준·프롬프트 버전, 설정, 반복 번호, 문장과 근거.

SDK(anthropic)는 call_judge 안에서만 불러온다. 나머지 함수는 SDK 없이 단위 테스트한다.
"""
from __future__ import annotations

import hashlib
import json
import re
import time
from pathlib import Path

from rag.faithfulness_rubric import AXES, GUIDE, VERSION as RUBRIC_VERSION
from run_record import EVAL_DIR

MODEL = "claude-sonnet-5-5"
PROMPT_VERSION = "judge-v2"  # v2: 판단 요령(GUIDE)과 성별 추가 (채점 기준 v1.1)
CACHE_DIR = EVAL_DIR / ".cache" / "judge"
# 100만 토큰당 달러. 출처: claude-api 스킬 shared/models.md, model-migration.md (2026-11-11 확인).
# 캐시 쓰기는 5분 TTL 가격이다
PRICE = {"input": 2.00, "output": 10.00, "cache_write": 2.50, "cache_read": 0.10}
# ADR 0009 4절: 보정셋에서 두 설정을 비교해 고른다.
# off: thinking 없음 (Sonnet 5.5는 {"type": "disabled"}가 400이라 between_tools로 끈다)
# low: adaptive thinking + effort low (thinking 토큰도 max_tokens와 출력 요금에 들어간다)
SETTINGS = {
    "off": {"thinking": {"type": "between_tools"}, "output_config": {}, "max_tokens": 2000},
    "low": {"thinking": {"type": "adaptive"}, "output_config": {"effort": "low"}, "max_tokens": 8000},
}

SCHEMA = {
    "type": "object",
    "properties": {
        "support": {
            "type": "object",
            "properties": {
                "label": {"type": "string", "enum": list(AXES["support"]["labels"])},
                "quotes": {"type": "array", "items": {
                    "type": "object",
                    "properties": {"source": {"type": "integer"}, "quote": {"type": "string"}},
                    "required": ["source", "quote"], "additionalProperties": False}},
                "unsupportedClaims": {"type": "array", "items": {"type": "string"}},
                "reason": {"type": "string"},
            },
            "required": ["label", "quotes", "unsupportedClaims", "reason"], "additionalProperties": False,
        },
        "band": {
            "type": "object",
            "properties": {"label": {"type": "string", "enum": list(AXES["band"]["labels"])},
                           "reason": {"type": "string"}},
            "required": ["label", "reason"], "additionalProperties": False,
        },
        "treatment": {
            "type": "object",
            "properties": {"label": {"type": "string", "enum": list(AXES["treatment"]["labels"])},
                           "quote": {"type": "string"}},
            "required": ["label", "quote"], "additionalProperties": False,
        },
    },
    "required": ["support", "band", "treatment"], "additionalProperties": False,
}


def system_prompt() -> str:
    """채점 기준. 모든 호출에 같은 글자여야 캐시가 맞는다 (날짜·id 같은 바뀌는 값을 넣지 않는다)"""
    lines = [
        "당신은 건강검진 결과 해설 문장의 의미 충실도를 채점한다. 해설 문장 하나와, 그 문장이 인용한 근거 문단,",
        "그리고 항목 정보(결과값, 국가건강검진 판정, 판정 기준)를 받는다. 아래 세 축을 각각 판정한다.",
        "",
        "판단 근거의 범위",
        "- '근거가 문장을 뒷받침하는가'는 그 문장이 인용한 근거 문단만 보고 판단한다. 항목 정보는 이 축의 근거가 아니다.",
        "  내용이 항목 정보와 맞아도, 인용한 근거 문단에서 확인되지 않으면 뒷받침되지 않은 것이다.",
        "- '판정 구간이 맞는가'는 항목 정보와 비교한다.",
        "- 일반 의학 상식으로 맞는 말이어도, 근거 문단에 없으면 뒷받침되지 않은 것이다.",
        "",
    ]
    lines.append("판단 요령")
    lines += [f"- {g}" for g in GUIDE]
    lines.append("")
    for axis_key, axis in AXES.items():
        lines.append(f"[{axis_key}] {axis['name']}: {axis['question']}")
        for value, (name, desc) in axis["labels"].items():
            lines.append(f"  - {value} ({name}): {desc}")
        lines.append("")
    lines += [
        "출력 규칙",
        "- support.quotes: 문장의 주장을 뒷받침하는 구절을 근거 문단에서 글자 그대로 복사한다 (source는 근거 번호).",
        "  고치거나 요약하거나 이어 붙이지 않는다. 코드가 원문과 글자 단위로 대조한다. 뒷받침하는 구절이 없으면 빈 배열.",
        "- support.unsupportedClaims: 근거 문단에서 확인되지 않는 주장을 짧게 적는다. 없으면 빈 배열.",
        "- treatment.quote: 치료·약 언급이 있으면 해당 부분을 문장에서 그대로 복사하고, 없으면 빈 문자열.",
        "- reason: 한국어 한두 문장으로 짧게.",
    ]
    return "\n".join(lines)


def user_message(item: dict, sentence: str, sources: list[dict]) -> str:
    unit = f" {item['unit']}" if item.get("unit") else ""
    sex = {"MALE": "남", "FEMALE": "여"}.get(item.get("sex") or "", "모름")
    parts = [
        "[항목 정보]",
        f"항목: {item['name']} | 결과값: {item['value']}{unit} | 판정: {item['judgmentLabel']} | 성별: {sex}",
        f"판정 기준: {item.get('criterion') or '없음'}",
        "",
        "[해설 문장]",
        sentence,
        "",
        "[인용한 근거]",
    ]
    for i, source in enumerate(sources, start=1):
        parts.append(f"[근거 {i}] ({source.get('publisher') or ''} · {source.get('section') or ''})")
        parts.append(source["text"])
    return "\n".join(parts)


def _squash(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def verify_quotes(quotes: list[dict], sources: list[dict]) -> list[dict]:
    """구절마다 근거 원문에 실제로 있는지 본다 (공백 차이만 허용)"""
    checked = []
    for q in quotes:
        index = q.get("source")
        text = sources[index - 1]["text"] if isinstance(index, int) and 1 <= index <= len(sources) else ""
        quote = _squash(q.get("quote") or "")
        checked.append({**q, "found": bool(quote) and quote in _squash(text)})
    return checked


def finalize(raw: dict, sources: list[dict]) -> dict:
    """채점기 출력에 구절 확인을 더한다. 지지·부분인데 확인된 구절이 없거나 틀린 구절이 있으면 최종 판정은 미지지"""
    quotes = verify_quotes(raw["support"]["quotes"], sources)
    label = raw["support"]["label"]
    verified = label == "unsupported" or (bool(quotes) and all(q["found"] for q in quotes))
    return {
        "support": label if verified else "unsupported", "supportRaw": label, "quoteVerified": verified,
        "quotes": quotes, "unsupportedClaims": raw["support"]["unsupportedClaims"],
        "band": raw["band"]["label"], "treatment": raw["treatment"]["label"],
        "treatmentQuote": raw["treatment"]["quote"],
        "reason": {"support": raw["support"]["reason"], "band": raw["band"]["reason"]},
    }


def cost_usd(usage: dict) -> float:
    return round((usage.get("input_tokens", 0) * PRICE["input"] + usage.get("output_tokens", 0) * PRICE["output"]
                  + usage.get("cache_creation_input_tokens", 0) * PRICE["cache_write"]
                  + usage.get("cache_read_input_tokens", 0) * PRICE["cache_read"]) / 1_000_000, 6)


def cache_key(setting: str, repeat: int, item: dict, sentence: str, sources: list[dict]) -> str:
    material = json.dumps({"model": MODEL, "rubric": RUBRIC_VERSION, "prompt": PROMPT_VERSION, "setting": setting,
                           "repeat": repeat, "item": item, "sentence": sentence,
                           "sources": [[s["id"], s["text"]] for s in sources]}, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:32]


def call_judge(client, setting: str, repeat: int, item: dict, sentence: str, sources: list[dict]) -> dict:
    """문장 하나 채점. 결과: {result, usage, costUsd, latencyMs, cached} 또는 {error, ...}"""
    key = cache_key(setting, repeat, item, sentence, sources)
    cache_file = CACHE_DIR / f"{key}.json"
    if cache_file.exists():
        return {**json.loads(cache_file.read_text(encoding="utf-8")), "cached": True}

    config = SETTINGS[setting]
    started = time.monotonic()
    response = client.messages.create(
        model=MODEL,
        max_tokens=config["max_tokens"],
        thinking=config["thinking"],
        output_config={"format": {"type": "json_schema", "schema": SCHEMA}, **config["output_config"]},
        system=[{"type": "text", "text": system_prompt(), "cache_control": {"type": "ephemeral"}}],
        messages=[{"role": "user", "content": user_message(item, sentence, sources)}],
    )
    latency_ms = int((time.monotonic() - started) * 1000)
    usage = {k: getattr(response.usage, k, 0) or 0 for k in
             ("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")}
    record = {"usage": usage, "costUsd": cost_usd(usage), "latencyMs": latency_ms, "stopReason": response.stop_reason}
    if response.stop_reason != "end_turn":  # 잘림(max_tokens)·거절(refusal)은 판정으로 쓰지 않는다. 캐시하지 않는다
        return {**record, "error": f"stop_reason={response.stop_reason}", "cached": False}
    text = next(block.text for block in response.content if block.type == "text")
    record["result"] = finalize(json.loads(text), sources)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_file.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
    return {**record, "cached": False}
