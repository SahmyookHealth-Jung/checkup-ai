package com.checkupai.report.retrieval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 근거 청크 파일(eval/rag/build_chunks.py가 만든 JSONL)을 읽는다.
 * 벡터 저장소 적재({@link GuidelineLoader})와 판정 해설의 근거 조회가 같은 파일을 같은 방식으로 읽는다.
 */
public final class GuidelineChunks {

    /** 판정기준표 행 청크의 절 이름 (data/guidelines/curated/mohw-2026-6-annex4.jsonl) */
    public static final String CRITERIA_ROW_SECTION = "검사항목별 판정기준";
    /** 조회한 근거의 점수. 유사도로 고른 것이 아니라 정해진 규칙으로 고른 근거다 */
    public static final double LOOKUP_SCORE = 1.0;

    private GuidelineChunks() {
    }

    public static List<RetrievedChunk> read(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalStateException("근거 청크 파일이 없다: " + file.toAbsolutePath().normalize()
                    + " (eval/에서 .venv/bin/python -m rag.build_chunks 로 만든다)");
        }
        var mapper = JsonMapper.builder().build();
        try {
            List<RetrievedChunk> chunks = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> chunk = mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) chunk.get("metadata");
                chunks.add(new RetrievedChunk(String.valueOf(chunk.get("id")), String.valueOf(chunk.get("text")),
                        LOOKUP_SCORE, metadata == null ? Map.of() : metadata));
            }
            return chunks;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
