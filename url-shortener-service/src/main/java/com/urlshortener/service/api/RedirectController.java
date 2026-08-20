package com.urlshortener.service.api;

import com.urlshortener.service.domain.ShortUrl;
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

    @Operation(summary = "Resolve a short code and redirect to the original URL")
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        ShortUrl entity = service.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(entity.getLongUrl()))
                .build();
    }
}
