# 화면 디자인 가이드: AI 티 안 나게 (2026-11-12)

6단계 화면(ADR 0012)을 세 번 거절당한 뒤 정리했다. 근거는 2026년 디자이너·도구 제작자 글이다(아래 출처). 대부분 실무자 의견이라 연구 결과는 아니다.

## 1. 왜 티가 났나

| 시안 | 사용자 반응 | 원인 |
|---|---|---|
| 1. 둥근 카드·알약 배지·숫자 타일 | 너무 AI스러움 | AI 기본값 1세대 그대로다. 가운데 정렬 히어로, 같은 크기 카드 3~4개, 의미 없는 숫자 타일, 배지 남발 |
| 2. 크림 바탕·명조 제목·번호 붙은 절 | 너무 딱딱함 | AI 기본값 2세대(크림·테라코타·편집 디자인 흉내)다. 형식만 보고서이고 내용이 들어갈 자리가 없다 |
| 3. 쇼핑몰 배치(짙은 두 줄 머리글, 노란 버튼, 상품 카드) | 티 나고 부자연스러움 | 남의 사이트 정체성을 빌려 왔다. 결과지 해설 서비스와 맞지 않는 장치(가격 자리, 상품 카드)가 내용을 밀어낸다 |

공통 원인은 **모양을 먼저 정하고 내용을 끼워 넣은 것**이다. 자주 지적되는 티는 이렇다.
- 보라·파랑 그라데이션, 그라데이션 제목 글자, 글래스모피즘, 이모지 아이콘.
- Inter·Roboto 하나로 제목과 본문을 다 씀(글꼴을 아무도 정하지 않았다는 신호).
- 가운데 정렬 히어로 + 버튼 두 개 + 같은 크기 카드 3개.
- 어느 제품에나 붙는 문구("더 빠르고 똑똑하게"), 숫자·이름·날짜·실제 화면이 없음.
- 모든 상자에 같은 둥근 모서리와 부드러운 그림자, 모든 절이 같은 격자.

## 2. 이 프로젝트의 방향

**"실제 결과가 주인공인, 조용한 한국어 제품 화면."** 꾸밈을 만들지 않고 이 서비스만 보여 줄 수 있는 것을 앞에 둔다. 실제 판정 줄, 출처가 붙은 실제 해설 문장, 실제로 거절한 질문, 못 채운 목표의 실제 숫자가 그것이다. 글꼴은 한국어 제품 화면에서 익숙한 Pretendard 하나에 굵기와 크기로만 위계를 준다. 색은 흰 바탕과 회색 중립색에, 검진 판정색(정상A 초록·경계 노랑·질환의심 빨강)과 강조색 하나(짙은 청록)만 쓴다. 배치는 일부러 고르지 않게 한다. 중요한 것은 크게, 나머지는 촘촘한 표와 목록으로 둔다.

## 3. 토큰

```css
:root {
  --bg: #ffffff; --bg-2: #f6f7f7; --text: #191f1d; --text-2: #4e5856; --muted: #7b8582;
  --line: #e4e8e7; --line-2: #ccd3d1;
  --accent: #0d6e5f; --accent-bg: #e8f3f1;           /* 짙은 청록 하나. 보라·파랑 그라데이션 금지 */
  --a: #1e8a4c; --b: #b07400; --s: #d1343a;            /* 판정색: 결과통보서의 정상A·경계·질환의심 */
  --radius: 8px;                                        /* 상자에만. 버튼은 6px, 배지 없음 */
  --font: "Pretendard Variable", Pretendard, -apple-system, "Apple SD Gothic Neo", sans-serif;
}
/* 크기: 13 / 15(본문) / 17 / 22 / 32. 줄 간격 본문 1.65, 제목 1.3. 제목 글자 사이 -0.02em */
/* 간격: 4·8·12·16·24·40·64. 절 사이는 64, 절 안 묶음 사이는 24 */
```

- 글꼴은 Pretendard(OFL, jsDelivr 동적 서브셋 한 줄)를 쓴다. 명조·고정폭 장식 글자는 쓰지 않는다. 숫자는 `tabular-nums`로 쓴다.
- 그림자는 쓰지 않고 1px 선을 쓴다. 바탕은 흰색이고, 묶음만 `--bg-2`로 구분한다.
- 어두운 모드는 같은 색상환에서 단계를 따로 고른다(뒤집지 않는다).

## 4. 화면별로 바꿀 점

**소개**
1. 첫 화면을 왼쪽 글, 오른쪽 **실제 결과 발췌**로 나눈다(비대칭). 발췌는 판정 줄, 출처가 붙은 해설 문장, 거절한 질문을 데모에서 실제로 나온 그대로 옮긴다.
2. 제목은 숫자가 든 구체적인 한 줄로 쓴다. 다른 서비스에 붙여도 말이 되는 문구는 쓰지 않는다.
3. 기능 카드 4개를 없앤다. "잘 된 것"과 "못 한 것"을 같은 무게로 둔 두 단 목록으로 바꾼다.
4. 처리 순서는 한 줄 흐름(→)과 "LLM이 하는 일 / 코드가 하는 일" 두 묶음으로 보여 준다.

**해설 데모**
1. 머리글은 흰 바탕 한 줄이다. 샘플 선택과 버튼은 제목 바로 아래 한 줄에 둔다.
2. 오른쪽 요약은 고정 상자 대신, 결과 위에 판정 개수를 한 줄로 쓴다("질환의심 3 · 경계 1 · 정상A 5").
3. 후속 질문 답은 대화 말풍선이나 상품 Q&A 대신 질문 → 처리 → 답 → 출처 순서의 간결한 목록으로 쓴다.
4. 처리 표시는 배지 대신 색 점 + 글자로 한다.

**품질**
1. 맨 위에 한 문단 요약을 둔다. "잘 된 것 3개, 못 한 것 2개"를 숫자로 적는다.
2. 대표 수치는 카드 격자 대신 표로 둔다. 상태 열은 색 점 + 글자다.
3. 후속 질문은 표를 주인공으로 하고, 막대는 한 줄 보조로 둔다.

**운영**
1. 큰 숫자 타일 3개 대신 문장형 요약 한 줄과 예산 막대 하나를 둔다.
2. 차트는 폭을 다 쓰고, 상자 테두리 없이 둔다.
3. 표는 촘촘하게 하고 숫자를 오른쪽에 맞춘다.

## 5. 하지 말 것

- 그라데이션(배경·글자·버튼), 글래스모피즘, 큰 그림자, 이모지 아이콘.
- 같은 크기 카드 3~4개 격자, 숫자만 큰 타일 격자, 알약 배지 남발.
- 크림·종이 바탕 + 명조 제목 + 고정폭 대문자 머리말(2세대 기본값).
- 남의 서비스 모양 빌리기(쇼핑몰 머리글, 노란 버튼, 상품 카드).
- "AI가 ~합니다", "더 빠르고 똑똑하게" 같은 어디에나 붙는 문구. 가짜 일러스트.
- 모든 절에 같은 격자와 같은 간격.

## 6. 참고

- 토스: Pretendard와 강조색 하나, 한 화면에 할 일 하나([디자인 시스템 갤러리](https://www.oppadu.com/tools/design-systems-site/brand/toss.html)).
- Linear·Vercel 문서와 대시보드: 흰 바탕, 1px 선, 촘촘한 표, 숫자 정렬, 실제 화면을 앞에 둠.
- Pretendard: [GitHub](https://github.com/orioncactus/pretendard) (OFL 1.1, CDN 동적 서브셋).

출처
- [AI Slop Fonts and Gradients: The Tells That Give Away AI Design (925 Studios)](https://www.925studios.co/blog/ai-slop-design-tells)
- [How to make a website not look AI generated (21st.dev)](https://21st.dev/blog/website-not-look-ai-generated)
- [Design slop (GIGAZINE, 2026-04-23)](https://www.gigazine.net/gsc_news/en/20260423-design-slop)
- "보라 그라데이션 다음은 크림·테라코타"라는 두 번째 기본값 이야기는 검색 결과 요약에서 본 것이라 원문을 확인하지 못했다
- [7 Signs a UI Has Been Vibe Coded (The Fountain Institute)](https://www.thefountaininstitute.com/blog/signs-vibe-coded-ui)
- [Why Your AI Keeps Building the Same Purple Gradient Website](https://prg.sh/ramblings/Why-Your-AI-Keeps-Building-the-Same-Purple-Gradient-Website)

## 부록: 4번째 시안 점검 (조사 세션, 2026-11-13, 아직 반영 안 한 제안)

위 1~6절로 다시 만든 현재 화면(커밋 0eb1207 기준)을 읽고, 실제 사이트에서 잰 값과 비교해 남은 틈만 적었다. 방향은 위와 같다. 3절의 청록 강조색을 바꾸자는 제안(A2의 1번)은 위 토큰과 충돌하니, 반영 여부는 따로 정한다.
### A1. AI 티 목록에 더할 것 (기존 가이드 1절 보충)

기존 목록(보라 그라데이션, Inter 기본값, 가운데 히어로 + 카드 3개, 숫자 타일, 크림·명조 2세대)은 맞다. 더할 것은 이렇다.

| 티 | 왜 티가 나나 |
|---|---|
| **주 버튼 + 테두리 버튼 두 개를 나란히** 둔 히어로 | 생성 도구가 히어로마다 넣는 짝이다. 할 일이 하나인 화면에서 둘을 같은 무게로 두면 고민 없이 만든 배치로 보인다 |
| **제목 위 작은 회색 머리말**(eyebrow, 예: "합성 데이터로 만든 포트폴리오 · 2026") | 히어로마다 붙는 생성 기본값이다. 정보가 있으면 본문 첫 줄로 내리면 된다 |
| **색이 장식이고 의미가 없음** | 강조색이 판정색과 같은 계열이면, 링크·버튼·막대가 "정상"처럼 읽힌다(아래 A2) |
| **행복한 경우만 있음** | 빈 화면·불러오는 중·실패·데이터 0건을 디자인하지 않았다. 생성 코드는 정적 코드를 보고 배워서 이런 상태를 빠뜨린다([prg.sh](https://prg.sh/ramblings/Why-Your-AI-Keeps-Building-the-Same-Purple-Gradient-Website)) |
| **모든 요소가 같은 크기·같은 간격** | 위계가 없다. 중요한 것은 더 크고, 가깝고, 무겁게 둔다([Kosta Canatselis](https://world.hey.com/kostac/spot-the-slop-a-ui-designer-s-guide-to-fixing-ai-defaults-4c448c9c)) |
| **어디에 붙여도 되는 문구**, 얼버무림("~할 수 있습니다", "도움이 될 수 있습니다") | 무엇도 약속하지 않는 생성 문장의 특징이다. 숫자·이름·날짜로 바꾼다([925 Studios](https://www.925studios.co/blog/ai-slop-web-design-guide)) |

**글꼴 자체는 티가 아니다.** Linear는 지금도 Inter를 쓴다(아래 A6에서 잰 값). 티는 굵기·자간·크기를 아무도 정하지 않은 **기본값**이다. Pretendard도 400/700만 쓰고 자간을 그대로 두면 똑같이 기본값으로 보인다.

### A2. 지금 화면에 남은 틈 (중요한 순서)

1. **강조색(청록)과 정상A(초록)가 같은 계열이다.** 밝은 모드 `--accent #0d6e5f` vs `--a #1e8a4c`, 어두운 모드 `#5cc3ae` vs `#5fcb8a`로 거의 같다. 판정 화면에서 초록은 "정상"이라는 뜻인데, 링크·주 버튼·`.bar-fill`·`.budget-fill`이 모두 같은 초록 계열이다. 그래서 품질 화면의 "기대대로 처리" 막대가 "목표 달성"처럼 읽힌다.
   → **색은 의미에만 쓴다.** 판정 3색과 차트 3색만 둔다. 상호작용은 잉크색으로 한다(주 버튼은 `--text` 바탕 + `--bg` 글자, 링크는 `--text` + 밑줄). 막대는 `--text-2` 회색이나 해당 시리즈색으로 바꾼다. 파랑은 `--series-1`, 보라는 AI 티라서 쓸 수 있는 강조 색상이 남지 않는다. 잉크가 가장 안전하다.
2. **고정 머리글이 앵커를 가린다.** `.nav`가 `position: sticky; height: 56px`라서, `#cite-3`·`#followup`으로 이동하면 제목이 머리글 밑에 숨는다.
   → `[id] { scroll-margin-top: 72px; }` 한 줄을 넣는다. 출처 번호를 누르면 해당 출처가 잠깐 보이게 `:target { background: var(--b-bg); }`도 넣는다. 사람이 만든 화면과 생성 화면이 갈리는 디테일이다.
3. **소개 히어로에 아직 1세대 짝이 남아 있다.** 머리말(kicker), 버튼 두 개.
   → 머리말은 지운다(내용은 본문에 이미 있다). 버튼은 "샘플로 해설 보기" 하나만 두고, "평가 기록"은 본문 속 링크로 둔다.
4. **품질 화면 후속 질문 절에 숫자 타일 3개(`#fu-tiles .stat`)와 32px 큰 숫자가 남아 있다.**
   → 큰 숫자 하나("40문항 중 36개 기대대로")만 남기고, 나머지 두 값은 그 아래 한 문장으로 쓴다. "답하면 안 되는데 답함 **2**건(목표 0), 정답 근거 인용 **8/18**(목표 80%)." 표(혼동 행렬)가 주인공이다.
5. **해설 문장이 본문보다 작다.** 이 서비스에서 가장 많이 읽는 글은 데모의 해설 문장인데 15px이다. Toss 글 목록은 15~16px, Linear 본문은 17px/27px이다.
   → `#items p`, 후속 질문 답에만 `16px / 1.75`를 쓴다. 표·보조 글은 지금처럼 13~14px로 둔다. 크기 차이가 위계를 만든다.
6. **진료 권고 상자가 색에만 기댄다.** `.advice`는 빨간 바탕 글 한 덩어리다. NHS 디자인 시스템 care card 규칙을 따른다. 첫 줄은 할 일을 말하는 제목이고("병원에서 확인해 보세요"), 색을 못 보는 사람도 제목으로 알 수 있어야 한다. 겁주지 않도록 한 항목에 하나만 둔다([NHS service manual](https://service-manual.nhs.uk/design-system/patterns/help-users-decide-when-and-where-to-get-care)).
   → `<b>`로 할 일을 한 줄 쓰고, 그 아래 이유를 쓴다. 빨간 바탕 대신 왼쪽 3px `--s` 선 + `--s-bg`를 쓴다(경고 상자와 구분).

### A3. 토큰 수정안 (기존 3절 대비 바뀌는 것만)

```css
:root {
  /* 상호작용은 잉크. 초록 계열 강조색을 없애 판정색(--a)과 겹치지 않게 한다 */
  --accent: var(--text); --accent-bg: var(--bg-2);
  --link-underline: color-mix(in srgb, var(--text) 35%, transparent);
  --read: 16px;          /* 해설·답 문장. 본문(15px)보다 한 단계 크게 */
  --read-lh: 1.75;
}
a { color: var(--text); text-decoration: underline; text-decoration-color: var(--link-underline);
    text-underline-offset: 3px; text-decoration-thickness: 1px; }
a:hover { text-decoration-color: currentColor; }
.nav a { text-decoration: none; }                     /* 머리글만 밑줄 없음 */
button { color: var(--bg); background: var(--text); border-color: var(--text); }
.bar-fill, .budget-fill { background: var(--text-2); }
[id] { scroll-margin-top: 72px; }
:target { background: var(--b-bg); transition: background 1.2s; }
h1, h2, h3 { text-wrap: balance; }                    /* 한국어 제목 줄바꿈을 고르게 (keep-all과 함께) */
p, li { text-wrap: pretty; }
.mono, code, td.mono { overflow-wrap: anywhere; }     /* 커밋 해시·샘플 ID·URL이 휴대폰에서 넘치지 않게 */
```

- 어두운 모드도 `--accent: var(--text)`이므로 따로 고를 것이 없다. 버튼은 밝은 글자 바탕에 어두운 글자로 자동으로 뒤집힌다.
- 글꼴 `@import`는 CSS를 다 받은 뒤에야 글꼴 CSS를 요청한다. 각 HTML `<head>`에 `<link rel="preconnect" href="https://cdn.jsdelivr.net">`와 `<link rel="stylesheet" href="…pretendardvariable-dynamic-subset.min.css">`를 두면 한 번 덜 기다린다(URL은 지금 쓰는 v1.3.9, 200 응답 확인). 선택 사항이다.
- 굵기는 400 / 600 / 700 세 단계로 둔다. 숫자 강조에만 700을 쓰고 제목은 650~700으로 둔다(가변 글꼴이라 650도 된다). Linear가 510/590 같은 중간 굵기로 기본값 느낌을 없앤 방식과 같다.

### A4. 화면별 바꿀 점 (현재 화면 기준)

**소개 (`index.html`)**
1. 머리말 `.kicker` 줄을 지운다. 버튼은 하나만 둔다(A2의 3번).
2. 실제 출력 발췌(`.sample`)는 이 화면의 핵심이고 잘 됐다. 발췌 머리의 "2026-11-12" 옆에 커밋이나 프롬프트 버전을 작은 고정폭으로 붙인다. 진짜 출력이라는 증거가 된다.
3. "된 것 / 안 된 것" 두 단은 좋다. 제목 색(초록/빨강)은 판정색이니 글자색 대신 앞의 색 점(`.pill` 방식)으로 바꾸고, 제목은 잉크로 둔다.
4. "누가 무엇을 하나"는 `1fr 2fr`로 비대칭이라 좋다. 각 항목 끝에 실제 근거 숫자(예: "원문에 없는 값 버림: heldout 146항목 중 조용히 지나감 0")를 하나씩 붙이면 설명이 증거가 된다.

**해설 데모 (`demo.html`)**
1. 처음 들어오면 결과가 비어 있다. 첫 샘플을 미리 골라 두고, 빈 상태 문구를 "왼쪽 위에서 고르고 해설 보기"처럼 할 일로 쓴다. 가능하면 지난 결과를 캐시에서 바로 보여 준다(10초 기다림 없이 내용부터).
2. 결과 표에서 정상A 줄은 `--text-2`로 흐리게 하고, 경계·질환의심 줄만 진하게 한다. 사람이 결과지를 볼 때 찾는 것만 눈에 띄게 한다.
3. 항목별 해설은 `#items p`를 16px/1.75로 하고(A2의 5번), 진료 권고는 care card 방식으로 바꾼다(A2의 6번).
4. 출처 번호 → 출처 이동에 `scroll-margin-top`과 `:target` 강조를 넣는다(A2의 2번). 출처 목록 각 줄에 "↩ 본문으로" 링크를 둔다.
5. 후속 질문의 처리 결과(`.d-*`)는 색 점 + 글자로 된 지금 방식을 유지한다. "모델은 답하려 했지만 규칙이 막았습니다" 같은 설명 줄은 이 서비스만 보여 줄 수 있는 내용이니 더 앞에, 답 바로 위에 둔다.

**품질 (`quality.html`)**
1. 맨 위 `#lede`는 결론 문장으로 쓴다. "기능 1은 목표 4개 중 3개를 채웠고, 후속 질문은 시험에서 진단 질문 2건에 답해 통과하지 못했습니다." 못 한 것을 첫 문장에 둔다.
2. 대표 수치 표(`.hl`)는 잘 됐다. "목표 미달" 줄만 행 바탕을 `--s-bg`로 아주 옅게 깐다. 수치 열 굵기는 600으로 한 단계 낮춘다(지금은 모든 줄이 700이라 강조가 없다).
3. 후속 질문 절의 숫자 타일 3개를 문장 한 줄로 바꾼다(A2의 4번).
4. 탭 버튼은 두 묶음(평가셋 / 항목 고름 여부) 앞에 작은 이름표("평가셋", "항목")를 붙인다. 구분선(`.sep`)만으로는 두 축이라는 것이 안 보인다.
5. "평가 기록 전체" 표는 `<details>`로 접고 요약에 건수를 쓴다("평가 기록 전체 · 41건"). 긴 고정폭 표가 페이지 끝을 차지하지 않게 한다.

**운영 (`ops.html`)**
1. 차트 제목을 이름("날짜별 비용")에서 발견으로 바꾼다. 데이터로 문장을 만든다. "14일 비용의 72%는 결과지 추출입니다." Our World in Data 차트가 제목을 문장으로, 부제에 단위와 정의를, 아래에 출처를 쓰는 방식이다.
2. 범례 대신 마지막 막대 오른쪽에 시리즈 이름을 직접 붙이거나, 아래 표의 "기능" 열 앞에 같은 색 점을 붙여 차트와 표를 묶는다.
3. 툴팁은 마우스에만 반응한다. 휴대폰에서는 막대를 눌러도 안 보이니 `click`에도 같은 핸들러를 건다. 아니면 "자세한 값은 아래 표"라는 한 줄을 둔다.
4. 데이터가 0건일 때(새로 띄운 서버)의 상태를 만든다. "아직 호출 기록이 없습니다. 해설 데모를 한 번 돌리면 여기 쌓입니다."
5. 기간 `<select>`는 7 / 14 / 30 글자 링크 세 개(현재 것 굵게)로 바꾼다. 선택지가 셋이면 열어 봐야 하는 상자보다 한눈에 보이는 편이 낫다.

### A5. 하지 말 것 (기존 5절에 더할 것)

- 판정색(초록·노랑·빨강)을 링크·버튼·진행 막대·제목 글자에 쓰기.
- 히어로 위 머리말, 히어로 버튼 두 개.
- 툴팁에만 있는 정보(휴대폰에서는 볼 수 없다).
- 행복한 경우만 디자인하기: 빈 상태·불러오는 중·실패·0건이 없는 화면.
- "손으로 그린 느낌"·질감·네오브루탈리즘 같은 2026 "반 AI" 유행을 따라 하기. 트렌드 글은 이것을 권하지만([Creative Bloq](https://www.creativebloq.com/design/fonts-typography/breaking-rules-and-bringing-joy-top-typography-trends-for-2026), [DesignRush](https://www.designrush.com/agency/website-design-development/trends/web-design-trends)), 의료 정보 화면에서는 신뢰를 깎는다. 이 프로젝트에서 사람 손은 **내용의 구체성과 작은 디테일**로 보여 준다.
- 크림 바탕(#faf9f5 계열) + 세리프. Anthropic 사이트의 색이고, 이를 흉내 낸 생성 화면이 많아 2세대 티가 됐다.

### A6. 참고 사이트와 배울 점 (직접 열어 계산된 스타일을 잰 값)

| 사이트 | 잰 값 | 배울 점 |
|---|---|---|
| [Linear 변경 기록](https://linear.app/changelog) | Inter Variable. h1 48px·굵기 510·자간 −1.06px, h2 32px·590·−0.70px, 본문 17px/27px `#d0d6e0` on `#08090a` | 글꼴이 아니라 **조율**이 티를 없앤다(중간 굵기, 음수 자간). 날짜를 왼쪽 단에, 내용을 오른쪽에 두는 비대칭 기록 배치는 품질 화면의 평가 기록에 쓸 만하다 |
| [토스 기술 블로그](https://toss.tech) | Toss Product Sans. 글자 `#191f28`, 보조 `#6b7684`, 글 제목 24px/700, 요약 15px/24px | 카드 없이 **분류 글자 + 제목 + 한 줄 요약** 목록으로 둔다. 회색 두 단계만으로 위계를 만든다. 지금 토큰(`--text #191f1d`, `--muted #7b8582`)이 이미 이 수준이다 |
| [Stripe 문서](https://docs.stripe.com/payments/accept-a-payment) | 시스템 글꼴. h1 32px/700 `#1a1f36`, 부제 24px/400, 코드 Menlo 14.4px | 시스템 글꼴과 높은 밀도로도 위계가 분명하면 고급스럽다. "개발자가 아니라면?" 같은 **이름표 붙은 안내 상자**, 빵부스러기, 왼쪽 목차를 배운다 |
| [NHS care cards](https://service-manual.nhs.uk/design-system/patterns/help-users-decide-when-and-where-to-get-care) | 비긴급(파랑)·긴급(빨강)·응급(빨강+짙은 회색) 3종 | 진료 권고 문구 규칙이다. 제목이 곧 할 일이다. 색에만 기대지 않는다. 겁주지 않게 개수를 줄인다. 상자 사이에 끼운 글은 사람들이 놓친다 |
| [Anthropic 연구](https://www.anthropic.com/research) | 바탕 `#faf9f5`, 글자 `#141413`, 보조 `#5e5d59`, 자체 산세리프 | 중립색에 아주 약한 온기를 넣는 법은 배울 만하다. 다만 이 조합을 그대로 쓰면 2세대 티다(A5) |
| [Our World in Data](https://ourworldindata.org/grapher/life-expectancy) | (이번에는 쿠키 창·로딩 때문에 차트를 재지 못함. 알려진 관례만 적음) | 차트 제목은 문장, 부제는 지표 정의와 단위, 아래는 출처, 범례 대신 선 끝 직접 이름표. 운영 화면 차트에 바로 쓴다 |

출처(트렌드·티 목록)
- [925 Studios: AI Slop Web Design guide (2026)](https://www.925studios.co/blog/ai-slop-web-design-guide)
- [Kosta Canatselis: Spot the slop](https://world.hey.com/kostac/spot-the-slop-a-ui-designer-s-guide-to-fixing-ai-defaults-4c448c9c)
- [Why Your AI Keeps Building the Same Purple Gradient Website](https://prg.sh/ramblings/Why-Your-AI-Keeps-Building-the-Same-Purple-Gradient-Website)
- [Anthropic frontend-design skill (미러)](https://www.mintlify.com/anthropics/skills/reference/frontend-design). 원문 저장소는 확인하지 못했다
- [DesignRush: Polished Is the New Generic](https://www.designrush.com/agency/website-design-development/trends/web-design-trends), [Creative Bloq: typography trends 2026](https://www.creativebloq.com/design/fonts-typography/breaking-rules-and-bringing-joy-top-typography-trends-for-2026)
- 대부분 에이전시·도구 회사 글이라 의견이다. 측정한 연구는 없다.
