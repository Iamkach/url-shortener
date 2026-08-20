package com.urlshortener.service.api;

import com.urlshortener.service.api.dto.AnalyticsResponse;
import com.urlshortener.service.service.ClickRecordingService;
import com.urlshortener.service.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls")
@RequiredArgsConstructor
public class AnalyticsController {

    private final UrlShortenerService urlShortenerService;
    private final ClickRecordingService clickRecordingService;

    @Operation(summary = "Get click analytics for a short code")
    @GetMapping("/{code}/analytics")
    public AnalyticsResponse analytics(@PathVariable String code) {
        // resolve() throws NoSuchElementException (-> 404) for an unknown code, matching US-1 AC2.
        urlShortenerService.resolve(code);
        long totalClicks = clickRecordingService.countClicks(code);
        return new AnalyticsResponse(code, totalClicks, clickRecordingService.lastAccessedAt(code).orElse(null));
    }
}
