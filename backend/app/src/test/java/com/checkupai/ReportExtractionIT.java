package com.checkupai;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.ReportExtractor;
import com.checkupai.report.extraction.ReportItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 추출 파이프라인 연결 확인 (짧은 입력). 정확도 평가는 eval/extraction 스크립트가 맡는다.
 * 실행 전제: PostgreSQL, Ollama, 채팅 모델(OLLAMA_CHAT_MODEL) pull 완료. CPU에서 1분 안팎 걸린다.
 * 오프라인 프로필(로컬 모델)로 돈다. 기본 공급자(Haiku)는 유료 API라 통합 테스트에서 부르지 않는다 (ADR 0005).
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("offline")
class ReportExtractionIT {

    @Autowired
    ReportExtractor extractor;

    @Test
    @DisplayName("짧은 결과지에서 표준 코드와 원문 값을 뽑고 측정값을 남긴다")
    void extractsShortReport() {
        String report = """
                검진일: 2026-10-14   성별: 여   나이: 만 51세
                검사항목 | 결과 | 단위 | 참고치
                공복혈당 | 112 | mg/dL | 100 미만
                요단백 | 음성 | - | 음성
                """;

        ExtractionResult result = extractor.extract(report);

        assertThat(result.report().items())
                .extracting(ReportItem::code, ReportItem::value)
                .contains(org.assertj.core.groups.Tuple.tuple(ItemCode.FASTING_GLUCOSE, "112"),
                        org.assertj.core.groups.Tuple.tuple(ItemCode.URINE_PROTEIN, "음성"));
        assertThat(result.metrics().latencyMs()).isPositive();
        assertThat(result.metrics().completionTokens()).isPositive();
    }
}
