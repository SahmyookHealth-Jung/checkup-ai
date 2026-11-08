package com.checkupai.report.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportTextReaderTest {

    private final ReportTextReader reader = new ReportTextReader();

    @Test
    @DisplayName("txt 파일은 UTF-8 텍스트로 읽는다")
    void readsTextFile() {
        byte[] content = "공복혈당 | 112 | mg/dL".getBytes(StandardCharsets.UTF_8);

        assertThat(reader.read(content, "report.TXT")).isEqualTo("공복혈당 | 112 | mg/dL");
    }

    @Test
    @DisplayName("글자 층이 있는 PDF에서 텍스트를 뽑는다")
    void readsPdfTextLayer() throws IOException {
        byte[] pdf = pdfWithText("FBS 112 mg/dL");

        assertThat(reader.read(pdf, "report.pdf")).contains("FBS", "112", "mg/dL");
    }

    @Test
    @DisplayName("글자 층이 없는 PDF는 지원하지 않는다고 알린다")
    void rejectsPdfWithoutText() throws IOException {
        byte[] pdf = pdfWithText(null);

        assertThatThrownBy(() -> reader.read(pdf, "scan.pdf"))
                .isInstanceOf(ReportTextReader.UnsupportedReportFormatException.class);
    }

    @Test
    @DisplayName("이미지 파일은 아직 지원하지 않는다")
    void rejectsImages() {
        assertThatThrownBy(() -> reader.read(new byte[] {1, 2, 3}, "photo.jpg"))
                .isInstanceOf(ReportTextReader.UnsupportedReportFormatException.class)
                .hasMessageContaining("photo.jpg");
    }

    private static byte[] pdfWithText(String text) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(72, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
