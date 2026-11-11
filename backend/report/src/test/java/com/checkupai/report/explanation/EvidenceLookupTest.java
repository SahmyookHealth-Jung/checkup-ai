package com.checkupai.report.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.retrieval.GuidelineChunks;
import com.checkupai.report.retrieval.RetrievedChunk;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 조회표와 실제 근거 청크 파일이 맞물리는지 확인한다 (청크를 다시 만들면 여기서 걸린다) */
class EvidenceLookupTest {

    /** 테스트 작업 폴더는 backend/report다 */
    private static final Path CHUNKS = Path.of("../../data/guidelines/chunks/chunks.jsonl");
    /** 판정기준표로 판정하는 17개 항목 (criteria/general-screening-2026-6.json) */
    private static final Set<ItemCode> JUDGED = EnumSet.of(ItemCode.BMI, ItemCode.WAIST_CIRCUMFERENCE,
            ItemCode.SYSTOLIC_BP, ItemCode.DIASTOLIC_BP, ItemCode.FASTING_GLUCOSE, ItemCode.TOTAL_CHOLESTEROL,
            ItemCode.HDL_CHOLESTEROL, ItemCode.TRIGLYCERIDES, ItemCode.LDL_CHOLESTEROL, ItemCode.HEMOGLOBIN,
            ItemCode.AST, ItemCode.ALT, ItemCode.GAMMA_GTP, ItemCode.URINE_PROTEIN, ItemCode.SERUM_CREATININE,
            ItemCode.EGFR, ItemCode.CHEST_XRAY);

    private final EvidenceLookup lookup = new EvidenceLookup(CHUNKS);

    @Test
    @DisplayName("조회표에 적은 절과 판정 구분 정의가 모두 근거 청크 파일에 있다")
    void tableMatchesChunkFile() {
        List<RetrievedChunk> chunks = GuidelineChunks.read(CHUNKS);
        Set<String> ids = chunks.stream().map(RetrievedChunk::id).collect(Collectors.toSet());
        assertThat(ids).containsAll(lookup.table().always().judgmentDefinition().values());
        for (EvidenceLookup.GuidelineDocument document : lookup.table().documents()) {
            for (String section : document.sections()) {
                assertThat(chunks).as(document.docId() + " / " + section)
                        .anyMatch(chunk -> document.docId().equals(chunk.metadataText("doc_id"))
                                && section.equals(chunk.metadataText("section")));
            }
        }
    }

    @Test
    @DisplayName("판정 대상 17개 항목은 모두 판정기준표 행을 근거로 찾는다")
    void everyJudgedItemHasCriteriaRow() {
        for (ItemCode code : JUDGED) {
            assertThat(lookup.evidence(code, Judgment.SUSPECT).chunks()).as(code.name()).first()
                    .satisfies(chunk -> {
                        assertThat(chunk.metadataText("section")).isEqualTo(GuidelineChunks.CRITERIA_ROW_SECTION);
                        assertThat(chunk.score()).isEqualTo(GuidelineChunks.LOOKUP_SCORE);
                    });
        }
    }

    @Test
    @DisplayName("코드 문구 대상(글 근거 없음)은 근거가 판정기준표 1행뿐인 10개 항목이다 (ADR 0008 1절)")
    void codeTemplateItemsMatchAdr() {
        Set<ItemCode> templated = JUDGED.stream().filter(code -> !lookup.hasGuideline(code))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ItemCode.class)));
        assertThat(templated).containsExactlyInAnyOrder(ItemCode.BMI, ItemCode.WAIST_CIRCUMFERENCE,
                ItemCode.HEMOGLOBIN, ItemCode.AST, ItemCode.ALT, ItemCode.GAMMA_GTP, ItemCode.URINE_PROTEIN,
                ItemCode.SERUM_CREATININE, ItemCode.EGFR, ItemCode.CHEST_XRAY);
        EvidenceLookup.Evidence ast = lookup.evidence(ItemCode.AST, Judgment.SUSPECT);
        assertThat(ast.chunks()).extracting(RetrievedChunk::id).containsExactly("mohw-annex4-row-ast");
        assertThat(ast.guideline()).isFalse();
    }

    @Test
    @DisplayName("글 근거가 있는 항목: 기준표 행, 판정에 맞는 정의, 조회표 순서대로의 절. 적지 않은 절은 오지 않는다")
    void guidelineItemEvidenceInOrder() {
        EvidenceLookup.Evidence evidence = lookup.evidence(ItemCode.FASTING_GLUCOSE, Judgment.NORMAL_B);
        assertThat(evidence.guideline()).isTrue();
        List<RetrievedChunk> borderline = evidence.chunks();

        assertThat(borderline).extracting(RetrievedChunk::id).startsWith("mohw-annex4-row-fasting-glucose",
                "mohw-annex4-def-normal-b", "kdca-5305-definition-1");
        assertThat(borderline).extracting(chunk -> chunk.metadataText("section")).containsOnly(
                GuidelineChunks.CRITERIA_ROW_SECTION, "판정 구분", "개요-정의", "진단 및 검사", "생활습관 관리");
        assertThat(borderline).hasSize(10);  // 행 1 + 정의 1 + 개요-정의 1 + 진단 및 검사 3 + 생활습관 관리 4

        assertThat(lookup.evidence(ItemCode.LDL_CHOLESTEROL, Judgment.SUSPECT).chunks()).extracting(RetrievedChunk::id)
                .startsWith("mohw-annex4-row-ldl", "mohw-annex4-def-suspect-chronic")
                .hasSize(6);  // 행 1 + 정의 1 + 개요-정의 1 + 진단 및 검사 1 + 치료-비약물 치료 2
    }
}
