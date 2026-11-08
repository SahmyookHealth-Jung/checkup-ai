// 모든 모듈이 공통으로 쓰는 빌드 규칙 (Java 21, BOM, 테스트 분리, .env 주입)
plugins {
    `java-library`
}

group = "com.checkupai"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(platform(libs.findLibrary("spring-boot-dependencies").get()))
    implementation(platform(libs.findLibrary("spring-ai-bom").get()))

    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

// 저장소 루트의 .env를 실행/테스트 환경변수로 주입한다. 셸에 이미 있는 환경변수가 우선한다.
// .env는 .gitignore 대상이며, 키 목록은 .env.example에 있다.
val dotEnv: Map<String, String> = providers
    .fileContents(rootProject.layout.projectDirectory.dir("..").file(".env"))
    .asText.orNull
    ?.lineSequence()
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
    ?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim().removeSurrounding("\"") }
    ?.filterKeys { System.getenv(it) == null }
    ?: emptyMap()

tasks.withType<JavaExec>().configureEach { environment(dotEnv) } // bootRun 포함

// 단위 테스트: 외부 의존(DB, Ollama, 외부 API) 없이 돈다
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
    failOnNoDiscoveredTests = false
    environment(dotEnv)
}

// 통합 테스트: docker compose의 PostgreSQL과 로컬 Ollama가 떠 있어야 한다
tasks.register<Test>("integrationTest") {
    description = "DB와 Ollama가 필요한 @Tag(\"integration\") 테스트를 실행한다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    failOnNoDiscoveredTests = false
    environment(dotEnv)
    shouldRunAfter(tasks.named("test"))
}
