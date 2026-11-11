package com.checkupai.report.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.ReportItem;
import com.checkupai.report.extraction.ReviewItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

/** 임시 근거 청크 파일과 가짜 모델로 판정 → 근거 조회 → 생성 → 검증 흐름을 확인한다 */
class ExplanationServiceTest {

    private static final String CRITERIA = "검사항목별 판정기준";
    private static final Map<String, Object> GLUCOSE_ROW = chunk("mohw-annex4-row-fasting-glucose",
            "검사항목별 판정기준 — 공복 혈당 / 정상A: 100 미만 / 정상B(경계): 100-125 / 질환의심: 126 이상",
            "mohw-2026-6-annex4", CRITERIA, List.of("FASTING_GLUCOSE"));
    private static final Map<String, Object> AST_ROW = chunk("mohw-annex4-row-ast",
            "검사항목별 판정기준 — AST / 정상A: 40 이하 / 질환의심: 51 이상", "mohw-2026-6-annex4", CRITERIA,
            List.of("AST"));
    private static final Map<String, Object> SUSPECT_DEFINITION = chunk("mohw-annex4-def-suspect-chronic",
            "판정 구분 — 고혈압·당뇨병·이상지질혈증 질환의심: 진료와 검사 등이 필요한 자", "mohw-2026-6-annex4", "판정 구분",
            List.of());
    private static final Map<String, Object> DIABETES_DIAGNOSIS = chunk("kdca-5305-diagnosis-1",
            "[당뇨병 | 진단 및 검사] 당뇨병은 혈당검사로 진단합니다.", "kdca-5305", "진단 및 검사", List.of("FASTING_GLUCOSE"));
    /** 조회표에 없는 절. 근거로 오면 안 된다 */
    private static final Map<String, Object> DIABETES_TYPES = chunk("kdca-5305-types-1",
            "[당뇨병 | 개요-종류] 1형당뇨병은 ...", "kdca-5305", "개요-종류", List.of("FASTING_GLUCOSE"));

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("질환의심 항목만 해설하고, 진료 권고·확인 필요·고지문은 코드가 붙인다. 인용은 번호로 바뀐다")
    void explainsSuspectItemWithCodeGuardrails() throws IOException {
        var model = new ScriptedChatModel("""
                {"items":[{"itemCode":"FASTING_GLUCOSE","sentences":[
                  {"text":"공복혈당 183 mg/dL은 판정기준의 126 이상 구간입니다.","sources":["S1"]},
                  {"text":"하루 3번 운동하세요.","sources":["S9"]}]}]}""");
        var service = service(model, List.of(GLUCOSE_ROW, SUSPECT_DEFINITION, DIABETES_DIAGNOSIS, DIABETES_TYPES));

        ExplanationResult result = service.explain(extraction(
                List.of(item(ItemCode.FASTING_GLUCOSE, "183", "mg/dL"), item(ItemCode.HEMOGLOBIN, "14.0", "g/dL")),
                List.of(new ReviewItem(ItemCode.AST, "AST", "6200", "가능 범위 밖: 6200"))));

        assertThat(result.judgments()).extracting(ExplanationResult.ItemJudgment::judgment)
                .containsExactly(Judgment.SUSPECT, Judgment.NORMAL_A);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo(ItemCode.FASTING_GLUCOSE);
            assertThat(item.sentences()).singleElement().satisfies(s -> assertThat(s.citations()).containsExactly(1));
            assertThat(item.careAdvice()).isEqualTo(ExplanationGuardrails.CARE_ADVICE);
            assertThat(item.criterionSource()).contains("보건복지부고시 제2026-6호");
            // 조회한 근거: 기준표 행, 질환의심 정의, 조회표에 적은 절. 적지 않은 절(개요-종류)은 오지 않는다
            assertThat(item.evidenceChunkIds()).containsExactly("mohw-annex4-row-fasting-glucose",
                    "mohw-annex4-def-suspect-chronic", "kdca-5305-diagnosis-1");
        });
        assertThat(result.citations()).singleElement()
                .satisfies(c -> assertThat(c.chunkId()).isEqualTo("mohw-annex4-row-fasting-glucose"));
        assertThat(result.needsReview()).singleElement()
                .satisfies(r -> assertThat(r.advice()).isEqualTo(ExplanationGuardrails.REVIEW_ADVICE));
        assertThat(result.disclaimer()).isEqualTo(ExplanationGuardrails.DISCLAIMER);
        assertThat(result.metrics().invalidCitations()).isEqualTo(1);
        assertThat(result.metrics().uncitedSentences()).isEqualTo(1);
        assertThat(model.calls).isEqualTo(1);
        assertThat(model.lastPrompt).doesNotContain("1형당뇨병");
    }

    @Test
    @DisplayName("조회한 근거가 없으면 LLM을 부르지 않고 '근거 없음'으로 답한다")
    void answersNoEvidenceWithoutCallingModel() throws IOException {
        var model = new ScriptedChatModel("{\"items\":[]}");
        var service = service(model, List.of(DIABETES_TYPES));

        ExplanationResult result = service.explain(extraction(List.of(item(ItemCode.FASTING_GLUCOSE, "130", "mg/dL")), List.of()));

        assertThat(result.items()).singleElement().satisfies(item -> assertThat(item.noEvidence()).isTrue());
        assertThat(model.calls).isZero();
        assertThat(result.disclaimer()).isNotBlank();
    }

    @Test
    @DisplayName("글 근거가 없는 항목은 LLM에 보내지 않고, 그 항목의 기준표 행을 인용한 고정 문장으로 답한다")
    void codeTemplateAnswersWithoutModel() throws IOException {
        var model = new ScriptedChatModel("""
                {"items":[{"itemCode":"FASTING_GLUCOSE","sentences":[
                  {"text":"공복혈당 183 mg/dL은 126 이상 구간입니다.","sources":["S1"]}]}]}""");
        var service = service(model, List.of(GLUCOSE_ROW, AST_ROW, SUSPECT_DEFINITION, DIABETES_DIAGNOSIS));

        ExplanationResult result = service.explain(extraction(
                List.of(item(ItemCode.FASTING_GLUCOSE, "183", "mg/dL"), item(ItemCode.AST, "55", "U/L")), List.of()));

        var ast = result.items().stream().filter(it -> it.code() == ItemCode.AST).findFirst().orElseThrow();
        assertThat(ast.sentences()).singleElement().satisfies(s -> {
            assertThat(s.text()).isEqualTo("AST(SGOT) 결과값(55 U/L)은 국가건강검진 판정기준의 질환의심 구간(51 이상)에 해당합니다.");
            assertThat(s.origin()).isEqualTo(ExplanationResult.Origin.CODE);
        });
        assertThat(ast.evidenceChunkIds()).containsExactly("mohw-annex4-row-ast");
        assertThat(ast.careAdvice()).isEqualTo(ExplanationGuardrails.CARE_ADVICE);
        assertThat(result.metrics().templatedItems()).isEqualTo(1);
        assertThat(result.metrics().sentencesGenerated()).isEqualTo(1);  // LLM 문장 수에 코드 문구는 넣지 않는다
        assertThat(model.calls).isEqualTo(1);
        assertThat(model.lastPrompt).doesNotContain("mohw-annex4-row-ast").doesNotContain("itemCode=AST")
                .doesNotContain("51 이상");  // 코드 문구 항목과 그 근거는 LLM에 보내지 않는다
    }

    @Test
    @DisplayName("조회표에 적힌 항목이어도 청크 파일에 그 절이 없으면 LLM에 보내지 않고 코드 문구로 답한다 (Codex 리뷰)")
    void fallsBackToCodeTemplateWithoutGuidelineChunks() throws IOException {
        var model = new ScriptedChatModel("{\"items\":[]}");
        // 기준표 행과 판정 구분 정의는 있지만, 조회표에 적은 절(개요-정의, 진단 및 검사, 생활습관 관리)이 없다
        var service = service(model, List.of(GLUCOSE_ROW, SUSPECT_DEFINITION, DIABETES_TYPES));

        ExplanationResult result = service.explain(extraction(List.of(item(ItemCode.FASTING_GLUCOSE, "183", "mg/dL")), List.of()));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.evidenceChunkIds()).containsExactly("mohw-annex4-row-fasting-glucose");
            assertThat(item.sentences()).singleElement()
                    .satisfies(s -> assertThat(s.origin()).isEqualTo(ExplanationResult.Origin.CODE));
        });
        assertThat(result.metrics().templatedItems()).isEqualTo(1);
        assertThat(model.calls).isZero();
    }

    @Test
    @DisplayName("판정기준표 행에서 그 판정 구간의 기준만 잘라 낸다")
    void bandTextCutsOwnBand() {
        assertThat(ExplanationService.bandText("간장질환 · AST · U/L · 정상A 40 이하 / 정상B(경계) 41-50 / 질환의심 51 이상",
                "질환의심")).isEqualTo("51 이상");
        assertThat(ExplanationService.bandText("비만 · BMI · kg/m2 · 정상A 18.5-24.9 / 정상B(경계) 25-29.9, 18.5 미만 / 질환의심 30 이상",
                "정상B(경계)")).isEqualTo("25-29.9, 18.5 미만");
        assertThat(ExplanationService.bandText("신장질환 · 요단백 · 정상A 음성(-) / 정상B(경계) 약양성(±) / 질환의심 양성(+1) 이상",
                "질환의심")).isEqualTo("양성(+1) 이상");
        assertThat(ExplanationService.bandText("x · 정상A 1 이하", "질환의심")).isNull();
    }

    private ExplanationService service(ChatModel model, List<Map<String, Object>> chunks) throws IOException {
        Path file = tempDir.resolve("chunks.jsonl");
        var mapper = JsonMapper.builder().build();
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            lines.add(mapper.writeValueAsString(chunk));
        }
        Files.write(file, lines);
        return new ExplanationService(ChatClient.builder(model), new ScreeningCriteria(), new EvidenceLookup(file),
                new ExplanationProperties(8, 4000));
    }

    private static Map<String, Object> chunk(String id, String text, String docId, String section,
                                             List<String> itemCodes) {
        return Map.of("id", id, "text", text, "metadata", Map.of("doc_id", docId, "title", "건강검진 실시기준",
                "publisher", "보건복지부", "section", section, "item_codes", itemCodes));
    }

    private static ExtractionResult extraction(List<ReportItem> items, List<ReviewItem> review) {
        var report = new CheckupReport(null, CheckupReport.ExamType.GENERAL, CheckupReport.Sex.MALE, 50, items);
        return new ExtractionResult(report, null, null, review);
    }

    private static ReportItem item(ItemCode code, String value, String unit) {
        return new ReportItem(code, code.koreanName(), value, unit, null, null);
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final String response;
        private int calls;
        private String lastPrompt = "";

        ScriptedChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            lastPrompt = prompt.getContents();
            return new ChatResponse(new ArrayList<>(List.of(new Generation(new AssistantMessage(response)))));
        }
    }
}
