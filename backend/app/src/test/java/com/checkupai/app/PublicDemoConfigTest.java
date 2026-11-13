package com.checkupai.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class PublicDemoConfigTest {

    private final PublicDemoConfig config = new PublicDemoConfig();

    private int status(String pattern, Object handler) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        MockHttpServletResponse response = new MockHttpServletResponse();
        config.preHandle(request, response, handler);
        return response.getStatus();
    }

    @Test
    @DisplayName("허용한 API와 정적 파일만 통과하고 나머지 API는 404다")
    void allowsOnlyListedApis() throws Exception {
        HandlerMethod api = new HandlerMethod(this, getClass().getDeclaredMethod("allowsOnlyListedApis"));

        assertThat(status("/api/demo/samples/{id}/explain", api)).isEqualTo(200);
        assertThat(status("/api/demo/samples/{id}/followup", api)).isEqualTo(200);
        assertThat(status("/api/ops/llm-usage", api)).isEqualTo(200);
        assertThat(status("/api/reports/explain", api)).isEqualTo(200);   // 결과지 올리기
        assertThat(status("/error", api)).isEqualTo(200);                 // 오류 응답 본문
        assertThat(status("/**", new Object())).isEqualTo(200);   // 정적 파일

        assertThat(status("/api/reports/extract", api)).isEqualTo(404);   // 예산 검사 없는 추출
        assertThat(status("/api/guidelines/reload", api)).isEqualTo(404);
        assertThat(status("/api/eval/explain", api)).isEqualTo(404);
        assertThat(status("/api/stats/ask", api)).isEqualTo(404);
        assertThat(status("/api/demo", api)).isEqualTo(404);              // 접두어가 아니라 /api/demo/ 아래만
        assertThat(status(null, api)).isEqualTo(404);
    }
}
