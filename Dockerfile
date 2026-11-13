# 공개 데모 이미지 (Render + Neon). 저장소 루트에서 빌드한다:
#   docker build -t checkup-ai-demo .
# gitignore 대상인 샘플 PDF와 근거 청크가 들어가므로 이미지는 비공개 레지스트리에만 올린다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY backend/ .
# 공개 이미지에서는 통계 화면을 빼고, 로컬 전용 요소(.local-only: 통계 링크, 결과지 올리기)를 숨긴다
RUN rm app/src/main/resources/static/stats.html \
 && echo '.local-only { display: none !important; }' >> app/src/main/resources/static/app.css \
 && ./gradlew :app:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/app/build/libs/app-0.0.1-SNAPSHOT.jar app.jar
COPY data/synthetic/extraction/v1/ data/samples/
COPY data/guidelines/chunks/chunks.jsonl data/chunks.jsonl
# 무료 인스턴스는 메모리가 작다: 직렬 GC로 상주 메모리를 줄인다
ENV SPRING_PROFILES_ACTIVE=public \
    DEMO_SAMPLES_DIR=/app/data/samples \
    GUIDELINE_CHUNKS_FILE=/app/data/chunks.jsonl \
    JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=70"
USER 1000
ENTRYPOINT ["java", "-jar", "app.jar"]
