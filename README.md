# checkup-ai

건강검진 결과를 공개 가이드라인 근거와 함께 해설하는 AI 서비스. 진단은 하지 않는다. Java 21 · Spring Boot 4 · Spring AI 2 · PostgreSQL/pgvector · Claude Haiku 5.5(기본) · Ollama(임베딩, 오프라인 모드).

> 진행 중인 포트폴리오 프로젝트입니다. 결과 중심의 전체 README는 6단계에서 작성합니다. 지금은 [프로젝트 브리프](docs/PROJECT_BRIEF.md)와 [개발 일지](docs/devlog.md)를 참고하세요.

## 빠른 시작

```bash
cp .env.example .env            # 값 채우기: POSTGRES_PASSWORD, ANTHROPIC_API_KEY (키는 .env에만, 커밋 금지)
docker compose up -d            # PostgreSQL + pgvector
ollama pull bge-m3              # 임베딩 모델 (Ollama 설치 필요)
cd backend && ./gradlew :app:bootRun
curl localhost:8080/actuator/health
```

- 채팅 모델 기본값은 Claude Haiku 5.5다. 외부로 보내기 전에 식별정보를 지우고, 호출마다 토큰·비용·지연을 기록한다 ([ADR 0005](docs/adr/0005-추출-모델-선택.md), [ADR 0006](docs/adr/0006-LLM-호출-기록과-식별정보-제거.md)).
- 오프라인 모드(로컬 모델, 데이터를 밖으로 보내지 않음): `ollama pull qwen3:4b` 후 `SPRING_PROFILES_ACTIVE=offline ./gradlew :app:bootRun`. Intel CPU에서는 결과지 하나에 약 5분이 걸린다.
- 이 저장소의 결과지는 모두 합성 데이터다.
