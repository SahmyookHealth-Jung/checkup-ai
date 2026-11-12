# CLAUDE.md

건강검진센터 AI 플랫폼 (AI 역량 포트폴리오). 전체 배경과 진행 단계는 [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md)에 있다. 주 단위가 아니라 단계 순서대로 진행한다.

- 기능 1 (진행 중): 건강검진 결과 해설 AI. 결과지 수치 추출(Structured Output), 공개 가이드라인 RAG, 출처 인용, 가드레일, 평가 자동화. 화면은 http://localhost:8080 (합성 샘플 데모).
- 기능 2 (7단계, 6단계 뒤에 할지 결정): 관리자용 Text-to-SQL 통계 어시스턴트.

## 절대 규칙

1. **데이터:** 공공데이터와 합성 데이터만 쓴다. 전 회사의 코드, DB 스키마, 실제 수검자 데이터는 어떤 형태로도 쓰지 않는다. 비슷한 것을 "기억나는 대로" 재현하지도 않는다.
2. **비밀값:** API 키와 비밀번호는 `.env`(gitignore 대상)에만 둔다. 코드, 설정 파일, 문서, 커밋 메시지에 적지 않는다. 새 키가 생기면 `.env.example`에 이름만 추가한다.
3. **의료:** 이 서비스는 진단하지 않는다. 아래 "의료 가드레일 원칙"을 모든 기능과 프롬프트에 반영한다.
4. **시스템 변경:** 소프트웨어 설치, 전역 설정 변경, 시스템 서비스 조작은 실행 전에 사용자 확인을 받는다.

## 협업 방식

- 큰 작업은 계획을 먼저 보여 주고, 승인을 받은 뒤 진행한다.
- **핵심 설계는 사용자가 결정한다.** RAG 구조, 청킹/검색 전략, 평가 지표, 프롬프트/가드레일, 모델 선택이 여기에 해당한다. Claude는 선택지 2~3개와 트레이드오프, 추천안을 제시하고, 결정되면 ADR로 남긴다.
- 커밋은 기능 하나에 하나씩 작게 한다. 커밋 전에 무엇을 바꿨는지 요약해 보여 준다.
- 커밋 메시지는 `feat:`, `fix:`, `docs:`, `chore:`, `test:`, `refactor:` 접두어 뒤에 한국어 요약을 쓴다.
- 주요 설계 결정은 `docs/adr/NNNN-제목.md`에 짧게 남긴다(맥락, 결정, 대안, 결과).
- 작업한 날에는 `docs/devlog.md`에 진행 내용, 측정치, Claude Code 협업에서 잘 된 점과 막힌 점을 남긴다.

## 개발 환경 (주의할 점)

- 머신은 **Intel Mac**이다(i7-8850H, 16GB, Apple Silicon 아님). Ollama는 GPU 없이 CPU로만 돈다. 로컬 채팅 모델은 소형급만 현실적이다.
- **채팅 모델 기본값은 Claude Haiku 5.5**다(ADR 0005). 키는 `.env`의 `ANTHROPIC_API_KEY`. 합성 데이터만 보낸다.
  - 오프라인 모드(로컬 qwen3:4b + 재요청): `SPRING_PROFILES_ACTIVE=offline`. 이 맥에서는 보고서당 약 5분이라 회귀 확인용으로만 쓴다.
  - 임베딩(bge-m3)은 두 모드 모두 로컬 Ollama다.
- 셸 기본 `java`는 JDK 24다. 프로젝트는 Gradle toolchain으로 **Java 21**을 고정한다.
- 로컬 PostgreSQL 17(EDB 설치판)이 5432를 쓰고 있다. 그래서 프로젝트 DB는 **5433**을 쓴다.
- Spring AI는 **2.x**다. 인터넷 예제 대부분은 1.x 기준이라 설정 키와 API가 다를 수 있다. 예: 임베딩 모델 키가 `spring.ai.ollama.embedding.options.model`에서 `spring.ai.ollama.embedding.model`로 바뀌었다. 새 설정을 쓰기 전에 jar 안의 `spring-configuration-metadata.json`이나 공식 문서로 확인한다.

## 명령어

```bash
docker compose up -d                       # PostgreSQL + pgvector (저장소 루트에서)
cd backend && ./gradlew build              # 컴파일 + 단위 테스트 (외부 의존 없음)
./gradlew :app:integrationTest             # 통합 테스트 (DB, Ollama 필요)
./gradlew :app:bootRun                     # 앱 실행 → http://localhost:8080/actuator/health
./gradlew :report:replay --args="<입력> <출력>"   # 재생 비교: 저장된 LLM 출력에 현재 검증기 적용 (eval/extraction/replay.py)
./gradlew :report:piiLeakCheck --args="<데이터 폴더> ..."   # 합성 결과지 식별정보 잔존율 (모델 호출 없음)
(cd ../eval && .venv/bin/python -m rag.build_chunks)        # 해설 근거 청크 만들기 → 앱에서 POST /api/guidelines/reload
(cd ../eval && .venv/bin/python -m rag.run_retrieval_eval)  # 검색 평가 (앱이 떠 있어야 함)
(cd ../eval && .venv/bin/python -m rag.freeze_extractions)  # heldout 30개 추출을 eval/frozen/에 고정 (Haiku 30회, 캐시 있으면 0회)
(cd ../eval && .venv/bin/python -m rag.run_explanation_eval --label <이름>)  # 고정한 추출로 해설만 30개 x 2회 (Haiku 54~60회, 조회 경로에서 약 $0.05)
(cd ../eval && .venv/bin/python -m rag.build_calibration_set)  # 충실도 보정셋 50문장 + 라벨링 화면(eval/.cache/labeling/, 커밋 안 함)
(cd ../eval && .venv/bin/python -m rag.run_faithfulness_judge --calibration calibration-v1 --setting off --repeats 3)  # 충실도 채점 (Sonnet 5.5, 호출당 약 $0.006)
(cd ../eval && .venv/bin/python -m rag.calibration_agreement --judge results/faithfulness/<runId>.json)  # 채점기와 사람 라벨의 κ
(cd ../eval && .venv/bin/python -m rag.build_seeded_set)  # 채점기 검증용 오류 심은 세트 141문장 + 검토 화면(eval/.cache/labeling/, 커밋 안 함)
(cd ../eval && .venv/bin/python -m rag.run_faithfulness_judge --calibration seeded-v1 --setting off --label seeded)  # 오류 심은 세트 채점 (새 호출 113회 약 $0.5)
(cd ../eval && .venv/bin/python -m rag.seeded_recall --judge results/faithfulness/<runId>.json)  # 채점기가 심은 오류를 잡은 비율과 통과 기준
(cd ../eval && .venv/bin/python -m synth.generate --templates labv --seed 20261112 --out ../data/synthetic/extraction/heldout-v4)  # 값·단위 표기 변형 평가셋 (정답은 manifest의 variants)
(cd ../eval && .venv/bin/python -m rag.run_notation_eval --frozen heldout-v4-v2.6 --label <이름>)  # 표기 변형 평가: 맞게 판정 / 확인 필요 / 틀린 판정 / 조용히 지나감
(cd ../eval && .venv/bin/python -m rag.freeze_replayed inputs --base <고정 추출> --out <폴더>/in)  # 검증 규칙만 바꿨을 때: 고정 추출의 LLM 출력을 재생 입력으로 (모델 호출 없음)
#   → ./gradlew :report:replay --args="<폴더>/in <폴더>/out" → rag.freeze_replayed write --base <고정 추출> --replayed <폴더>/out --name <새 이름> --pipeline <버전>
(cd ../eval && .venv/bin/python -m rag.compare_conditions --base <기준> --new <조건> --label <이름>)  # 조건 짝비교 (전체 + 근거가 바뀐 항목)
(cd ../eval && .venv/bin/python -m unittest discover -s rag -t .)            # 평가 지표 단위 테스트
```

평가 결과는 `eval/results/<종류>/<runId>.json`(공통 형식, `eval/run_record.py`)으로 남고 `eval/results/index.jsonl`에 한 줄씩 쌓인다. 비교 수치는 커밋한 코드로만 잰다(기록의 `git.dirty`가 false).

Gradle이 실행·테스트 환경변수로 저장소 루트의 `.env`를 읽어 넣는다(`buildSrc`의 컨벤션 플러그인).

## 저장소 구조

```
checkup-ai/
├── backend/                 Gradle 멀티모듈 (Kotlin DSL)
│   ├── buildSrc/            공통 빌드 규칙 (checkupai.java-conventions)
│   ├── gradle/libs.versions.toml   버전은 여기서만 관리
│   ├── core/                공통 기반: AI 호출 관측·비용, 공통 가드레일, 예외
│   ├── report/              기능 1: 결과지 추출 + RAG 해설
│   ├── stats/               기능 2: Text-to-SQL (7단계에 추가)
│   └── app/                 실행 모듈: 조립, 설정(application.yml), Flyway 마이그레이션, 최소 화면(정적 HTML, ADR 0007)
├── eval/                    Python 평가 스크립트·평가셋 (응답 캐시는 gitignore)
├── data/guidelines/         공개 가이드라인 출처 목록 (원문은 raw/에 내려받고 gitignore)
├── data/synthetic/          합성 검진 결과지
├── docs/                    PROJECT_BRIEF, adr/, devlog
└── docker-compose.yml
```

### 모듈 의존 규칙

- `report → core`, `stats → core`, `app → 전부`.
- 기능 모듈끼리는 서로 참조하지 않는다(`report ↛ stats`).
- 기능 모듈과 `core`는 Spring AI **추상화**에만 의존한다(`ChatClient`, `ChatModel`, `EmbeddingModel`, `VectorStore`). 공급자 스타터(Ollama, Anthropic, pgvector)는 `app`만 가진다. 모델 교체는 `application.yml`의 `spring.ai.model.*`로 한다.

## 코드 규칙

- 기본 패키지는 `com.checkupai.<모듈>`이다. 모듈 안에서는 기능(유스케이스) 단위로 하위 패키지를 나눈다. 예: `report.extraction`, `report.retrieval`, `report.explanation`. 최상위를 `controller/service/repository` 같은 계층으로 나누지 않는다.
- DTO와 값 객체는 `record`로 만든다. 요청과 응답은 `*Request` / `*Response`로 짓는다.
- 설정값은 `@ConfigurationProperties` + record(`*Properties`)로 묶는다. 필드에 `@Value`를 흩어 쓰지 않는다.
- 생성자 주입만 쓴다. Lombok은 쓰지 않는다.
- 테스트
  - 단위 테스트는 `*Test`, 외부 의존이 있는 테스트는 `*IT` + `@Tag("integration")`으로 만든다.
  - `@DisplayName`은 한국어로 쓴다.
- DB
  - 테이블과 컬럼은 snake_case로 짓는다.
  - 스키마는 Flyway(`app/src/main/resources/db/migration/V{n}__{설명}.sql`)로만 바꾼다.
- LLM을 호출하는 모든 코드는 토큰, 비용, 지연시간을 기록한다. 기록 방식은 `core`에 둔다(`LlmCallLoggingAdvisor`, 테이블 `llm_call_log`).

## 의료 가드레일 원칙

아래 원칙은 고정이다. **구현 방식**(프롬프트, 규칙 기반 후처리, 분류기 등)은 사용자가 결정해 ADR로 남긴다.

1. **진단하지 않는다.** "○○병입니다", "○○이 확실합니다"처럼 단정하지 않는다. "수치가 가이드라인의 어떤 범위에 해당하고, 가이드라인은 이렇게 설명한다"까지만 말한다.
2. **근거 없이 말하지 않는다.** 모든 해설에 출처(문서명, 발행기관, 쪽/절)를 붙인다. 검색 결과에 근거가 없으면 없다고 답하고 추측하지 않는다.
3. **위험 수치는 진료를 권고한다.** 위험 수준 판정 기준과 방식은 ADR로 정한다.
4. **모든 응답에 고지를 넣는다.** "이 내용은 의료 진단이 아니며, 정확한 판단은 의료진과 상담하세요."
5. **치료 지시를 하지 않는다.** 처방, 약물 용량, 복용 중단·변경, 특정 치료법을 권하지 않는다.
6. **기준치는 출처에서 가져온다.** 코드나 프롬프트에 의학적 기준 수치를 하드코딩할 때는 출처 문서를 주석으로 남긴다.
7. **개인정보를 최소화한다.** 합성 결과지만 쓰고, 로그에는 결과지 원문 대신 필요한 메타데이터만 남긴다.

## 검진 도메인 용어집 (초안)

기준 수치는 여기에 적지 않는다. 가이드라인 문서에서 출처와 함께 가져온다.

| 용어 | 영문/약어 | 설명 |
|---|---|---|
| 수검자 | examinee | 검진을 받는 사람 |
| 검진기관 | screening center | 검진을 수행하는 기관 |
| 일반건강검진 | general health screening | 국민건강보험공단이 시행하는 국가건강검진의 기본 검진 |
| 암검진 | cancer screening | 국가암검진 (위·대장·간·유방·자궁경부·폐암) |
| 결과통보서 | result report | 검진 결과와 판정을 수검자에게 알리는 문서. 이 프로젝트의 "결과지" |
| 문진표 | questionnaire | 병력, 생활습관 등을 묻는 사전 설문 |
| 판정 | judgment | 정상A, 정상B(경계), 질환의심, 유질환자 등의 결과 구분 (정확한 체계는 공단 자료로 확인 필요) |
| 재검 / 확진검사 | follow-up test | 이상 소견 시 추가로 받는 검사 |
| 신체계측 | anthropometry | 신장, 체중, 허리둘레, 체질량지수(BMI) |
| 혈압 | BP (SBP/DBP) | 수축기/이완기 혈압 |
| 공복혈당 | FBS / FPG | 8시간 이상 금식 후 혈당 |
| 총콜레스테롤 | TC | 혈중 콜레스테롤 총량 |
| HDL / LDL 콜레스테롤 | HDL-C / LDL-C | 고밀도/저밀도 지단백 콜레스테롤 |
| 중성지방 | TG | 트리글리세리드 |
| AST / ALT | SGOT / SGPT | 간 기능 관련 효소 수치 |
| 감마지티피 | γ-GTP (GGT) | 간·담도 관련 효소 수치 |
| 혈색소 | Hb | 빈혈 판단에 쓰는 혈액 검사 수치 |
| 혈청 크레아티닌 | Cr | 신장 기능 관련 수치 |
| 신사구체여과율 | eGFR | 크레아티닌 등으로 추정한 신장 기능 지표 |
| 요단백 | urine protein | 소변 내 단백질 검사 |
| 흉부방사선 | chest X-ray | 폐결핵 등 흉부 질환 선별 |

### AI 용어 (이 프로젝트에서 쓰는 뜻)

| 용어 | 뜻 |
|---|---|
| 청크 | 가이드라인 문서를 검색 단위로 자른 조각 |
| 하이브리드 검색 | 키워드 검색과 벡터 검색 결과를 합치는 방식 |
| 리랭킹 | 1차 검색 결과를 더 정밀한 모델로 다시 정렬하는 단계 |
| 평가셋 | 질문, 기대 근거, 기대 답변 특성을 묶은 50~100개 테스트 케이스 |
| LLM-as-a-Judge | LLM이 답변을 기준표(rubric)로 채점하는 자동 평가 |
