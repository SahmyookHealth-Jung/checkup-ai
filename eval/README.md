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
