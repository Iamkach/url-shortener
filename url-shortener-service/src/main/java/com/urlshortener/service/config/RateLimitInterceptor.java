package com.urlshortener.service.config;

import com.urlshortener.service.service.RateLimitExceededException;
import com.urlshortener.service.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientKey = request.getRemoteAddr();
        if (!rateLimiter.tryConsume(clientKey)) {
            throw new RateLimitExceededException("Rate limit exceeded, try again later");
        }
        return true;
    }
}
