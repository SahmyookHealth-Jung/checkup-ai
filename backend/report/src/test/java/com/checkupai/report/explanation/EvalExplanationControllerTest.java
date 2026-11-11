package com.checkupai.report.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.ReportItem;
import com.checkupai.report.extraction.ReviewItem;
import com.checkupai.report.retrieval.GuidelineProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class EvalExplanationControllerTest {

    private final ExplanationService service = mock(ExplanationService.class);

    @Test
    @DisplayName("평가 API를 끄면 404이고 해설(LLM)을 부르지 않는다")
    void disabledReturnsNotFound() {
        var controller = controller(false);

        assertThatThrownBy(() -> controller.explain(extraction()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(controller::info).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("저장한 추출 결과의 report와 확인 필요 항목만 해설에 넘긴다")
    void passesReportAndNeedsReviewOnly() {
        controller(true).explain(extraction());

        var captor = ArgumentCaptor.forClass(ExtractionResult.class);
        verify(service).explain(captor.capture());
        ExtractionResult passed = captor.getValue();
        assertThat(passed.report().items()).extracting(ReportItem::code).containsExactly(ItemCode.FASTING_GLUCOSE);
        assertThat(passed.needsReview()).extracting(ReviewItem::code).containsExactly(ItemCode.AST);
        assertThat(passed.metrics()).isNull();
        assertThat(passed.diagnostics()).isNull();
    }

    @Test
    @DisplayName("report가 없으면 400")
    void rejectsMissingReport() {
        assertThatThrownBy(() -> controller(true).explain(new ExtractionResult(null, null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(service, org.mockito.Mockito.never()).explain(any());
    }

    @Test
    @DisplayName("설정 조회는 해설 결과를 바꾸는 값(프롬프트 버전, 항목 상한, 근거 조회 방식과 조회표 해시)을 돌려준다")
    void infoReportsSettings() {
        var info = controller(true).info();

        assertThat(info.promptVersion()).isEqualTo(ExplanationService.PROMPT_VERSION);
        assertThat(info.maxItems()).isEqualTo(8);
        assertThat(info.evidence()).isEqualTo("lookup");
        assertThat(info.evidenceLookupSha256()).hasSize(16);
    }

    private EvalExplanationController controller(boolean enabled) {
        return new EvalExplanationController(service, new EvalProperties(enabled), new ExplanationProperties(8, 4000),
                new GuidelineProperties("chunks.jsonl", 4, 0.5));
    }

    private static ExtractionResult extraction() {
        var report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, 45,
                List.of(new ReportItem(ItemCode.FASTING_GLUCOSE, "공복혈당", "131", "mg/dL", null, null)));
        var metrics = new ExtractionResult.Metrics("m", "p", "pl", 1, 1, 1, 1, 0, Map.of(), 1, 1, 1);
        return new ExtractionResult(report, metrics, null,
                List.of(new ReviewItem(ItemCode.AST, "AST", "6200", "가능 범위 밖: 6200")));
    }
}
