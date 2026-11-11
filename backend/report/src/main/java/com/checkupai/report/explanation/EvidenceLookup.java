package com.checkupai.report.explanation;

import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.retrieval.GuidelineChunks;
import com.checkupai.report.retrieval.GuidelineProperties;
import com.checkupai.report.retrieval.RetrievedChunk;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 판정 해설의 근거를 조회한다 (ADR 0010 1절, 2026-11-11). 벡터 검색을 하지 않는다.
 *
 * <p>판정 해설은 입력이 닫혀 있다: 항목 17개 × 판정. 질의가 늘 같으니 검색이 아니라 조회다. 검색할 때는 유사도가
 * 0.50~0.70에 몰려 임계값이 아무것도 가르지 못했고, 다른 항목의 기준표 행이 근거 자리를 채웠다(2026-11-10 기준선).
 *
 * <p>근거는 조회표({@value #RESOURCE})로 정한다. 청크 id가 아니라 문서와 절 이름으로 적어, 청크를 다시 만들어도 그대로 쓴다.
 * <ul>
 *   <li>모든 항목: 그 항목의 판정기준표 행</li>
 *   <li>조회표에 글이 있는 항목(혈압 2, 공복혈당, 지질 4): 판정 구분 정의 하나와 정해 둔 절의 청크. 이 항목만 LLM이 문장을 쓴다</li>
 *   <li>나머지: 근거가 기준표 행뿐이라 코드 문구로 답한다 (ADR 0008 1절)</li>
 * </ul>
 * 청크는 근거 청크 파일에서 바로 읽는다. 파일이 바뀌면 다시 읽는다.
 *
 * <p>LLM에 보낼지는 조회표가 아니라 실제로 찾은 글 청크로 정한다 ({@link Evidence#guideline()}). 조회표에 적힌 항목이어도
 * 청크 파일에 그 절이 없으면 코드 문구로 답한다. 2026-11-11 Codex 리뷰: 조회표만 보면 청크를 다시 만들거나 다른 파일을
 * 쓸 때 기준표 행만으로 LLM 문장을 쓰게 된다.
 */
@Component
public class EvidenceLookup {

    private static final Logger log = LoggerFactory.getLogger(EvidenceLookup.class);

    static final String RESOURCE = "explanation/evidence-lookup.json";

    record Table(String description, Always always, List<GuidelineDocument> documents) {
    }

    /** @param judgmentDefinition 판정 → 판정 구분 정의 청크 id */
    record Always(String criteriaRow, Map<Judgment, String> judgmentDefinition) {
    }

    record GuidelineDocument(String docId, String title, List<ItemCode> itemCodes, List<String> sections) {
    }

    /**
     * 조회한 근거.
     *
     * @param chunks 근거 청크. 순서: 판정기준표 행, (글 근거가 있으면) 판정 구분 정의, 조회표에 적은 절 순서대로의 청크
     * @param guideline 글 청크를 하나라도 찾았다. 이때만 LLM이 문장을 쓴다. 아니면 근거는 기준표 행뿐이고 코드 문구로 답한다
     */
    public record Evidence(List<RetrievedChunk> chunks, boolean guideline) {
    }

    private record Loaded(FileTime modified, List<RetrievedChunk> chunks) {
    }

    private final Table table;
    private final Path chunksFile;
    private volatile Loaded loaded;

    @Autowired
    public EvidenceLookup(GuidelineProperties properties) {
        this(Path.of(properties.chunksFile()));
    }

    EvidenceLookup(Path chunksFile) {
        this.chunksFile = chunksFile;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.table = JsonMapper.builder().build().readValue(in, Table.class);
        } catch (IOException e) {
            throw new UncheckedIOException("근거 조회표를 읽지 못했다: " + RESOURCE, e);
        }
    }

    /** 조회표에 글 근거가 적혀 있다. 실제로 LLM에 보낼지는 {@link Evidence#guideline()}로 정한다 */
    public boolean hasGuideline(ItemCode code) {
        return document(code).isPresent();
    }

    /**
     * 그 항목·판정의 근거. 찾지 못한 근거는 빠진다. 기준표 행도 없으면 빈 목록이고, 해설은 "근거 없음"이 된다.
     */
    public Evidence evidence(ItemCode code, Judgment judgment) {
        List<RetrievedChunk> chunks = chunks();
        List<RetrievedChunk> row = chunks.stream()
                .filter(chunk -> GuidelineChunks.CRITERIA_ROW_SECTION.equals(chunk.metadataText("section"))
                        && hasItemCode(chunk, code))
                .limit(1)
                .toList();
        Optional<GuidelineDocument> document = document(code);
        if (document.isEmpty()) {
            return new Evidence(row, false);
        }
        List<RetrievedChunk> guideline = new ArrayList<>();
        for (String section : document.get().sections()) {
            chunks.stream()
                    .filter(chunk -> document.get().docId().equals(chunk.metadataText("doc_id"))
                            && section.equals(chunk.metadataText("section")))
                    .forEach(guideline::add);
        }
        if (guideline.isEmpty()) {
            log.warn("근거 조회표와 청크 파일이 어긋난다: {}의 절 {}이 {}에 없다. {}은 코드 문구로 답한다",
                    document.get().docId(), document.get().sections(), chunksFile, code);
            return new Evidence(row, false);
        }
        List<RetrievedChunk> found = new ArrayList<>(row);
        String definitionId = table.always().judgmentDefinition().get(judgment);
        chunks.stream().filter(chunk -> chunk.id().equals(definitionId)).findFirst().ifPresent(found::add);
        found.addAll(guideline);
        return new Evidence(found, true);
    }

    /** 조회표 파일. 평가 기록에 해시를 남긴다 (조회표가 바뀌면 근거가 바뀐다) */
    static ClassPathResource resource() {
        return new ClassPathResource(RESOURCE);
    }

    Table table() {
        return table;
    }

    private Optional<GuidelineDocument> document(ItemCode code) {
        return table.documents().stream().filter(document -> document.itemCodes().contains(code)).findFirst();
    }

    private static boolean hasItemCode(RetrievedChunk chunk, ItemCode code) {
        return chunk.metadata().get("item_codes") instanceof Collection<?> codes
                && codes.stream().anyMatch(value -> code.name().equals(String.valueOf(value)));
    }

    private List<RetrievedChunk> chunks() {
        try {
            FileTime modified = Files.getLastModifiedTime(chunksFile);
            Loaded current = loaded;
            if (current == null || !current.modified().equals(modified)) {
                current = new Loaded(modified, GuidelineChunks.read(chunksFile));
                loaded = current;
            }
            return current.chunks();
        } catch (IOException e) {
            throw new IllegalStateException("근거 청크 파일을 읽지 못했다: " + chunksFile.toAbsolutePath().normalize(), e);
        }
    }
}
