# 개발 일지

## 2026-11-08 (1주차 1일): 환경 구성과 프로젝트 골격

### 한 일

- **개발 환경 점검**
  - 이 머신이 브리프의 가정(Apple Silicon)과 달리 **Intel Mac**(i7-8850H, 16GB)인 것을 확인했다.
  - Homebrew는 Intel macOS용 미리 빌드된 포뮬러를 더 이상 주지 않는다. 그래서 cask로 설치했다.
    - Ollama: `ollama-app` 0.40.15
    - JDK: `temurin@21` 21.0.13
  - Docker Desktop 28.0.4는 이미 설치돼 있었다.
- **스택 확정:** start.spring.io에서 Spring Boot 3.x가 빠져 있었다. 그래서 Boot 4.1.2 + Spring AI 2.0.1로 정했다([ADR 0001](adr/0001-기술스택-선정.md)).
- **구조 확정:** Gradle 멀티모듈(`core` / `report` / `app`, 이후 `stats`)로 정했다([ADR 0002](adr/0002-저장소-모듈-구조.md)).
- **Docker Compose:** PostgreSQL 18.6 + pgvector 0.8.7을 띄웠다. Flyway V1으로 `vector` 확장을 켰다.
- **Spring AI 연결 확인:** 통합 테스트 3개가 통과했다.
  - 앱 기동과 pgvector 확장 확인
  - bge-m3 임베딩이 1024차원으로 나옴
  - 의미가 가까운 문장의 코사인 유사도가 더 높음
- **Ollama 내부 구조 확인:** 구조는 `Ollama.app → ollama serve(:11434) → llama-server(러너)` 3단이다.
  - 러너는 요청이 올 때 모델을 메모리에 올리고, 5분 동안 요청이 없으면 내린다(keep_alive).
  - 모델은 Docker 이미지처럼 manifest와 sha256 이름의 blob으로 저장된다.

### 측정 (Intel CPU, Ollama 0.40.15, bge-m3 F16)

| 항목 | 값 |
|---|---|
| bge-m3 첫 호출 (모델 로딩 포함) | 3.45초 |
| bge-m3 이후 호출 (두 문장 입력) | 0.57~0.63초 |
| 러너 메모리 (RSS) | 약 1.2GB |
| Ollama 서버 시작 시 여유 메모리 | 4.7GB (Docker에 8GB 할당된 상태) |
| Ollama 러너 컨텍스트 | 4096토큰 (모델 최대 8192) |

**시사점**
- 지연시간을 잴 때는 콜드/웜을 구분해야 한다.
- 문서 적재 때는 배치 처리와 임베딩 캐시를 고려해야 한다.
- 청크 크기는 4096토큰 안에서 정해야 한다.

### 결정
- Ollama는 컨테이너가 아니라 macOS 네이티브로 실행한다(Intel CPU 추론 성능 때문).
- 로컬 PostgreSQL 17(EDB 설치판)이 5432를 쓰고 있어 프로젝트 DB는 5433을 쓴다.
- 벡터 테이블 설계는 2주차로 미뤘다. 메타데이터와 키워드 검색 컬럼은 하이브리드 검색 설계와 함께 정한다.

### Claude Code 협업

**잘 된 점**
- 버전과 설정 키를 기억이나 블로그 대신 실제 소스에서 확인했다. 그래서 잘못된 가정 두 가지를 일찍 잡았다.
  - start.spring.io 메타데이터를 조회해 Boot 3.x가 없다는 것을 시작 전에 알았다.
  - jar 안의 `spring-configuration-metadata.json`을 읽어, Spring AI 2.0에서 `embedding.options.model`이 deprecated된 것을 확인했다. 1.x 예제를 그대로 따랐다면 경고가 났을 것이다.
- 환경 점검 단계에서 하드웨어(Intel)를 확인했다. 덕분에 모델 전략을 일찍 조정할 수 있었다.

**막힌 점**
- Homebrew 5.0.16에서 cask 조회가 내부 오류로 실패했다. `brew update`로 해결했다.
- `sudo` 비밀번호 입력란에 아무것도 표시되지 않아 입력이 안 되는 것처럼 보였다(정상 동작).
- **포트 충돌:** 사전 점검에서 `lsof`로 5432가 비어 있다고 판단했지만 실제로는 사용 중이었다.
  - 원인: `lsof`는 다른 사용자(postgres) 소유 프로세스를 보여 주지 않는다.
  - 교훈: 포트 점검은 `netstat -an`으로 한다.

### 다음
- 결과지 → JSON 추출(Structured Output)
  - 결과지 스키마 설계
  - 합성 결과지 샘플 만들기
  - 로컬 채팅 모델 후보 실측
- AI 호출 관측(토큰·비용·지연시간) 기록 방식 설계
