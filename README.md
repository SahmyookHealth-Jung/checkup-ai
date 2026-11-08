# checkup-ai

건강검진 결과를 공개 가이드라인 근거와 함께 해설하는 AI 서비스. 진단은 하지 않는다. Java 21 · Spring Boot 4 · Spring AI 2 · PostgreSQL/pgvector · Ollama.

> 진행 중인 포트폴리오 프로젝트입니다. 전체 README는 6주차에 작성합니다. 지금은 [프로젝트 브리프](docs/PROJECT_BRIEF.md)와 [개발 일지](docs/devlog.md)를 참고하세요.

## 빠른 시작

```bash
cp .env.example .env            # 값 채우기 (POSTGRES_PASSWORD 등)
docker compose up -d            # PostgreSQL + pgvector
ollama pull bge-m3              # 임베딩 모델 (Ollama 설치 필요)
cd backend && ./gradlew :app:bootRun
curl localhost:8080/actuator/health
```
