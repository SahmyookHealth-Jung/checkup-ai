# eval

평가 스크립트와 합성 데이터 생성기. Python 3.12, 가상환경은 `eval/.venv` (gitignore 대상).

```bash
cd eval
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

## synth: 합성 검진 결과지 생성기 (ADR 0003)

정답 JSON을 먼저 만들고, 레이아웃 템플릿 3종으로 텍스트와 PDF를 렌더링합니다. 실제 수검자 데이터는 쓰지 않습니다.

```bash
.venv/bin/python -m synth.generate --out ../data/synthetic/extraction/v1
```

| 템플릿 | id 접두어 | 특징 |
|---|---|---|
| 공단 표준 서식형 | `std` | 표 형식, 혈압은 `132/84` 한 칸, 항목별 판정 |
| 기관 요약표형 | `sum` | 종합검진, 영문 약어(FBS, r-GTP), **전회 결과 칸**, 국가검진 외 항목(→ `OTHER`), 판정 없음 |
| 목록 + 의사 소견형 | `nar` | 한 줄에 여러 항목, 일상어(키, 몸무게), **나이 없음**(생년월일만), **소견 문장 속 전회 수치** |

- 같은 시드(`--seed`, 기본 20261108)로 같은 결과가 나옵니다. 시드는 보고서마다 따로 잡아서, 개수를 바꿔도 기존 보고서는 그대로입니다.
- PDF는 macOS의 AppleGothic 폰트를 씁니다. 다른 폰트를 쓰려면 `--font` 또는 환경변수 `SYNTH_FONT`로 바꿉니다.
  - 폰트가 들어간 PDF는 커밋하지 않습니다(gitignore). 텍스트와 정답 JSON만 커밋합니다.
- 생성할 때 정답 JSON의 모든 문자열이 문서에 찍혀 있는지 검사합니다. 어긋나면 생성이 실패합니다.
- 값 구간과 참고치(`item_spec.yaml`)는 **임시값**입니다. 문서를 꾸미는 용도일 뿐, 해설의 의학적 근거로 쓰지 않습니다.

### 일반화 시험용 양식 (held-out)

검증 규칙은 위 3종 30개를 보면서 만들었습니다. 그래서 처음 보는 양식에서의 성능은 따로 잽니다.

```bash
.venv/bin/python -m synth.generate --templates lab --seed 20261109 --out ../data/synthetic/extraction/heldout-v1
.venv/bin/python -m synth.generate --templates trend --seed 20261110 --out ../data/synthetic/extraction/heldout-v2
```

| 평가셋 | 템플릿 | id 접두어 | 특징 | 쓰임 |
|---|---|---|---|---|
| heldout-v1 | 검사실 결과지형 | `lab` | 신체계측 **2단 표**(한 줄에 두 항목, 쉼표 없음), 시력·청력 좌우를 한 칸에(`1.0/0.8`), 열 순서 결과·판정·참고범위·단위, 판정 어휘 **정상/경계/이상**, 항목명 `공복혈당(FBS)`, 등록번호·채혈일·전화번호 | 검증 v2.2 일반화 시험. 결과를 봤으므로 이후로는 '본 양식' |
| heldout-v2 | 3개년 추이표형 | `trend` | 한 행에 **2024·2025·2026 세 해 값**(이번 값은 세 번째), 단위가 값 앞 열, `시력: 좌 1.0 우 0.8 / 청력: …` 한 줄, 판정 어휘 **정상/주의/재검**, 고객번호·대표번호·상담 시간 | 검증 v2.3 일반화 시험 (v2.3 전에 생성) |

- 이 양식의 결과를 보고 검증 규칙을 고치면 더는 처음 보는 양식이 아닙니다. 규칙을 고쳤다면 다음 일반화 시험에는 새 양식을 씁니다.

## extraction: 추출 평가

대표 지표는 항목 F1(code + value)입니다. 결과는 `results/extraction/history.md`에 한 줄씩 쌓입니다.

```bash
eval/run_eval_resumable.sh smoke   # 6개, 로컬 CPU 약 40분
eval/run_eval_resumable.sh full    # 30개, 로컬 CPU 약 3~4시간
```

- **중간에 꺼져도 같은 명령을 다시 실행하면 이어서 합니다.**
  - 보고서마다 응답을 `.cache/extraction/`에 저장하기 때문에, 끝난 보고서는 모델을 다시 부르지 않습니다.
  - 캐시 키는 (모델, 프롬프트 버전, 파이프라인 버전, 입력 형식, 파일 내용)입니다. 검증 로직을 바꾸면 파이프라인 버전(`ExtractionProperties`)을 올려야 새로 계산합니다.
- DB·Ollama·앱이 꺼져 있으면 스크립트가 켭니다. 평가가 도는 동안만 `caffeinate`로 잠자기를 막습니다.
- 진행 상황은 `tail -f eval/.cache/logs/full-latest.log`로 볼 수 있습니다.
- 다른 평가셋이나 특정 보고서만 돌릴 때는 인자를 뒤에 붙입니다. 경로는 `eval/` 기준입니다.
  - `LOG_NAME=heldout eval/run_eval_resumable.sh full --data ../data/synthetic/extraction/heldout-v1`
  - `eval/run_eval_resumable.sh full --ids sum-008`
- 보조 지표로 **필드 정확도**(값이 맞은 항목의 단위·참고치·판정)를 함께 기록합니다. 오류는 지어냄, 빠뜨림, 다름으로 나눕니다.
