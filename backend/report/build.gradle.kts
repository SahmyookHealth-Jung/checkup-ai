plugins {
    id("checkupai.java-conventions")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.spring.ai.pdf.document.reader)  // 결과지 PDF 글자 추출 (PDFBox)
    implementation(libs.spring.web)                     // 컨트롤러 애너테이션, MultipartFile
}
