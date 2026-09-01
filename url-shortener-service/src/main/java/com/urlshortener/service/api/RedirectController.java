package com.urlshortener.service.api;

import com.urlshortener.service.domain.ShortUrl;
import com.urlshortener.service.service.ClickRecordingService;
import com.urlshortener.service.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final UrlShortenerService service;
    private final ClickRecordingService clickRecordingService;

    @Operation(summary = "Resolve a short code and redirect to the original URL")
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        // Soft-expire (spec 003, C3): the row is kept and metadata stays readable via
        // UrlController -- only the redirect itself is blocked once expiresAt has passed.
        ShortUrl entity = service.resolveUnexpired(code);
        ResponseEntity<Void> response = ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(entity.getLongUrl()))
                .build();
        // Fired after the response is built so a slow/failing analytics write never delays the redirect (spec.md B4).
        clickRecordingService.recordAsync(code);
        return response;
    }
}
