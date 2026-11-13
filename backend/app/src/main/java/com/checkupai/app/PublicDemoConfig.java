package com.checkupai.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 공개 데모(SPRING_PROFILES_ACTIVE=public)에서는 허용한 API만 연다. 나머지 컨트롤러 메서드는 404다.
 * 주소 문자열이 아니라 Spring이 고른 매핑 패턴으로 판단하므로, 새 API는 여기 적기 전까지 닫혀 있다.
 * 액추에이터는 이 인터셉터를 거치지 않는다. application-public.yml에서 health만 남긴다.
 */
@Configuration
@Profile("public")
public class PublicDemoConfig implements WebMvcConfigurer, HandlerInterceptor {

    static boolean allowed(String pattern) {
        // /error: 허용한 API의 400·500 응답이 오류 화면 컨트롤러를 거친다. 막으면 모든 오류가 빈 404가 된다
        // /api/reports/explain: 화면의 결과지 올리기 (예산 검사를 거친다, ADR 0015 보완). 예산 검사 없는 /api/reports/extract는 닫아 둔다
        return pattern != null && (pattern.startsWith("/api/demo/") || pattern.equals("/api/ops/llm-usage")
                || pattern.equals("/api/reports/explain") || pattern.equals("/error"));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // 정적 파일(HandlerMethod가 아님)은 그대로 둔다
        if (!(handler instanceof HandlerMethod)
                || allowed((String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))) {
            return true;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return false;
    }
}
