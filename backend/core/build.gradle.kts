plugins {
    id("checkupai.java-conventions")
}

dependencies {
    // 기능 모듈이 공급자(Ollama/Claude)와 무관하게 쓰는 Spring AI 추상화
    api(libs.spring.ai.client.chat)
    api(libs.spring.ai.model)

    // LLM 호출 기록(ADR 0006): 메타데이터를 DB에 남긴다. 테이블은 app 모듈의 Flyway가 만든다
    implementation(libs.spring.jdbc)
    implementation(libs.spring.boot)  // @ConfigurationProperties (모델별 가격표)
}
