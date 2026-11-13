package com.checkupai.report.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.checkupai.report.extraction.ExtractionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DemoSamplesTest {

    private final DemoSamples samples = new DemoSamples(new DemoProperties("없는-폴더"), null, null);

    private static ExtractionResult extraction() {
        return new ExtractionResult(null, null, null, List.of());
    }

    @Test
    @DisplayName("올린 결과지는 받은 id로 같은 추출 결과를 돌려준다")
    void remembersUpload() {
        ExtractionResult uploaded = extraction();
        String id = samples.remember(uploaded);
        assertThat(id).matches("up-[0-9a-f]{24}");
        assertThat(samples.extraction(id)).isSameAs(uploaded);
    }

    @Test
    @DisplayName("기억하지 않은 업로드 id는 404, 형식이 틀린 id는 400")
    void unknownUpload() {
        assertThatThrownBy(() -> samples.extraction("up-" + "0".repeat(24)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> samples.extraction("up-../x"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    @DisplayName("올린 결과지는 최근 MAX_UPLOADS개만 기억한다")
    void evictsOldest() {
        String first = samples.remember(extraction());
        for (int i = 0; i < DemoSamples.MAX_UPLOADS; i++) {
            samples.remember(extraction());
        }
        assertThatThrownBy(() -> samples.extraction(first)).hasMessageContaining("404");
    }
}
