package com.checkupai.report.retrieval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 근거 청크 파일을 읽어 둔다. 파일이 바뀌면 다시 읽는다.
 * 판정 해설의 조회(explanation.EvidenceLookup)와 후속 질문의 문맥 만들기(followup)가 같은 파일을 같은 방식으로 읽는다.
 */
@Component
public class GuidelineCorpus {

    /** 판정기준표 행과 판정 구분 정의가 든 고시 문서 */
    public static final String CRITERIA_DOC = "mohw-2026-6-annex4";
    /** 판정 구분 정의의 절 이름 */
    public static final String JUDGMENT_DEFINITION_SECTION = "판정 구분";

    private record Loaded(FileTime modified, List<RetrievedChunk> chunks) {
    }

    private final Path chunksFile;
    private volatile Loaded loaded;

    @Autowired
    public GuidelineCorpus(GuidelineProperties properties) {
        this(Path.of(properties.chunksFile()));
    }

    public GuidelineCorpus(Path chunksFile) {
        this.chunksFile = chunksFile;
    }

    public List<RetrievedChunk> chunks() {
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

    public Path chunksFile() {
        return chunksFile;
    }

    /**
     * 청크가 속한 근거 단위. 고시는 행(청크) 하나, 나머지는 "문서#절".
     * 후속 질문 정답셋의 근거 라벨과 같은 규칙이다 (eval/rag/followup_evidence.py의 unit_of).
     */
    public static String unitOf(RetrievedChunk chunk) {
        String doc = chunk.metadataText("doc_id");
        return CRITERIA_DOC.equals(doc) ? chunk.id() : doc + "#" + chunk.metadataText("section");
    }
}
