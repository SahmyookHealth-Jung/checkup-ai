package com.checkupai.report.extraction;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 결과지 추출 API.
 *
 * <pre>
 * curl -F file=@std-001.pdf localhost:8080/api/reports/extract
 * curl -H 'Content-Type: text/plain;charset=UTF-8' --data-binary @std-001.txt localhost:8080/api/reports/extract
 * </pre>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportExtractionController {

    private final ReportTextReader textReader;
    private final ReportExtractor extractor;

    public ReportExtractionController(ReportTextReader textReader, ReportExtractor extractor) {
        this.textReader = textReader;
        this.extractor = extractor;
    }

    @PostMapping(path = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtractionResult extractFile(@RequestPart("file") MultipartFile file) throws IOException {
        return extractor.extract(textReader.read(file.getBytes(), file.getOriginalFilename()));
    }

    @PostMapping(path = "/extract", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ExtractionResult extractText(@RequestBody String reportText) {
        return extractor.extract(reportText);
    }

    @ExceptionHandler(ReportTextReader.UnsupportedReportFormatException.class)
    ProblemDetail unsupportedFormat(ReportTextReader.UnsupportedReportFormatException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }
}
