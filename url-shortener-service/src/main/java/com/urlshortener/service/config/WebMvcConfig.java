package com.urlshortener.service.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Exact-path match only: "/api/urls" (POST, create). Reads live under "/api/urls/{code}"
        // and "/{code}", which this pattern does not match, so they're naturally exempt (spec.md B3).
        // NOTE: do not add excludePathPatterns("/api/urls/**") here -- Ant-style "/**" matches zero
        // segments too, so it would also exclude the bare "/api/urls" path this interceptor targets.
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/urls");
    }
}
