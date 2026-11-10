plugins {
    id("checkupai.java-conventions")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.spring.ai.pdf.document.reader)  // 결과지 PDF 글자 추출 (PDFBox)
    implementation(libs.spring.web)                     // 컨트롤러 애너테이션, MultipartFile
    implementation(libs.spring.boot)                    // @ConfigurationProperties
}

// 재생 비교 (ADR 0004 5절): 저장된 LLM 출력에 현재 검증기를 다시 적용한다. 모델을 부르지 않는다.
// 입력은 eval/extraction/replay.py가 만든다. 사용: ./gradlew :report:replay --args="<입력 폴더> <출력 폴더>"
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "저장된 LLM 출력에 현재 검증기를 다시 적용한다 (재생 비교)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.checkupai.report.extraction.ExtractionReplay")
}
