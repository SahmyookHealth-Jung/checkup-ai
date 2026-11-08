plugins {
    id("checkupai.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":report"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    // 모델 공급자는 application.yml의 spring.ai.model.* 설정으로 고른다
    implementation(libs.spring.ai.starter.model.ollama)
    implementation(libs.spring.ai.starter.model.anthropic)
    implementation(libs.spring.ai.starter.vector.store.pgvector)
}
