package com.checkupai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring AI를 거쳐 로컬 Ollama의 bge-m3를 호출하는 연결 확인 테스트.
 * 실행 전제: Ollama 실행 중, {@code ollama pull bge-m3} 완료, PostgreSQL 실행 중.
 */
@Tag("integration")
@SpringBootTest
class OllamaEmbeddingIT {

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    @DisplayName("bge-m3는 1024차원 벡터를 돌려준다")
    void returns1024DimensionVector() {
        float[] vector = embeddingModel.embed("공복혈당 126 mg/dL 이상이면 당뇨병 의심으로 판정한다.");

        assertThat(vector).hasSize(1024);
    }

    @Test
    @DisplayName("의미가 가까운 문장이 먼 문장보다 코사인 유사도가 높다")
    void similarSentenceIsCloserThanUnrelatedSentence() {
        float[] query = embeddingModel.embed("공복혈당 수치가 높게 나왔어요");
        float[] related = embeddingModel.embed("공복혈당 126 mg/dL 이상이면 당뇨병 의심으로 판정한다.");
        float[] unrelated = embeddingModel.embed("흉부 X선 검사에서 폐결핵 소견은 없다.");

        assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
