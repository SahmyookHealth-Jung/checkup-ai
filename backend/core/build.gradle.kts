plugins {
    id("checkupai.java-conventions")
}

dependencies {
    // 기능 모듈이 공급자(Ollama/Claude)와 무관하게 쓰는 Spring AI 추상화
    api(libs.spring.ai.client.chat)
    api(libs.spring.ai.model)
}
