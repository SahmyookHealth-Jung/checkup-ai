package com.checkupai.report.extraction;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

/**
 * 업로드된 결과지 파일을 LLM에 넘길 텍스트로 바꾼다.
 *
 * <p>지원 형식: 텍스트(.txt), 글자 층이 있는 PDF. 이미지(스캔)는 3주차 이후 지원 (ADR 0003).
 */
@Component
public class ReportTextReader {

    public String read(byte[] content, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt")) {
            return new String(content, StandardCharsets.UTF_8);
        }
        if (name.endsWith(".pdf")) {
            return readPdf(content);
        }
        throw new UnsupportedReportFormatException(filename);
    }

    private String readPdf(byte[] content) {
        var reader = new PagePdfDocumentReader(new ByteArrayResource(content));
        String text = reader.get().stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));
        if (text.isBlank()) {
            // 글자 층이 없는 스캔 PDF
            throw new UnsupportedReportFormatException("글자 층이 없는 PDF(스캔 이미지)");
        }
        return text;
    }

    public static class UnsupportedReportFormatException extends RuntimeException {
        public UnsupportedReportFormatException(String what) {
            super("지원하지 않는 결과지 형식: " + what + " (지원: .txt, 글자 층이 있는 .pdf)");
        }
    }
}
