plugins {
    id("checkupai.java-conventions")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.spring.jdbc)   // 읽기 전용 계정으로 SQL 실행 (ADR 0013)
    implementation(libs.spring.web)    // 컨트롤러 애너테이션
    implementation(libs.spring.boot)   // @ConfigurationProperties
}
