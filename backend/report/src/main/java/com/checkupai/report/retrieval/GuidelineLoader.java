package com.checkupai.report.retrieval;

import com.checkupai.core.privacy.PersonalInfoRedactor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 근거 청크 파일을 벡터 저장소에 적재한다. 임베딩은 VectorStore가 로컬 bge-m3로 만든다.
 * 같은 문서의 이전 청크는 지우고 다시 넣는다(청킹을 바꿔 다시 적재해도 낡은 청크가 남지 않게).
 *
 * <p>적재하면서 각 청크에 식별정보 제거기를 돌려 바뀌는 청크를 센다 (ADR 0007 8절). 근거 문단은 시스템 메시지로
 * 보내 제거 Advisor를 거치지 않지만, 근거 문단에 전화번호·주소 모양이 있는지 알고 있어야 한다.
 */
@Service
public class GuidelineLoader {

    private final VectorStore vectorStore;
    private final GuidelineProperties properties;
    private final PersonalInfoRedactor redactor;

    public GuidelineLoader(VectorStore vectorStore, GuidelineProperties properties, PersonalInfoRedactor redactor) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.redactor = redactor;
    }

    /**
     * @param chunksByDocument 문서별 청크 수
     * @param redactionAffected 식별정보 제거기가 바꾸는 청크 id와 종류별 개수 (0건이어야 근거를 사용자 메시지에 넣어도 원문이 유지된다)
     * @param elapsedMs 임베딩과 저장에 걸린 시간
     */
    public record LoadResult(int chunks, Map<String, Integer> chunksByDocument,
                             Map<String, Map<String, Integer>> redactionAffected, long elapsedMs) {
    }

    public LoadResult load() {
        long started = System.nanoTime();
        List<Document> documents = readChunks(Path.of(properties.chunksFile()));
        Map<String, Integer> byDocument = new TreeMap<>();
        Map<String, Map<String, Integer>> affected = new LinkedHashMap<>();
        for (Document document : documents) {
            byDocument.merge(String.valueOf(document.getMetadata().get("doc_id")), 1, Integer::sum);
            var redaction = redactor.redact(document.getText());
            if (redaction.total() > 0) {
                Map<String, Integer> counts = new TreeMap<>();
                redaction.counts().forEach((type, count) -> counts.put(type.name(), count));
                affected.put(document.getId(), counts);
            }
        }
        if (!byDocument.isEmpty()) {
            vectorStore.delete(new FilterExpressionBuilder().in("doc_id", byDocument.keySet().toArray()).build());
        }
        vectorStore.add(documents);
        return new LoadResult(documents.size(), byDocument, affected, (System.nanoTime() - started) / 1_000_000);
    }

    private static List<Document> readChunks(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalStateException("근거 청크 파일이 없다: " + file.toAbsolutePath().normalize()
                    + " (eval/에서 .venv/bin/python -m rag.build_chunks 로 만든다)");
        }
        var mapper = JsonMapper.builder().build();
        try {
            List<Document> documents = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> chunk = mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) chunk.get("metadata");
                documents.add(Document.builder()
                        .id(String.valueOf(chunk.get("id")))
                        .text(String.valueOf(chunk.get("text")))
                        .metadata(metadata)
                        .build());
            }
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
