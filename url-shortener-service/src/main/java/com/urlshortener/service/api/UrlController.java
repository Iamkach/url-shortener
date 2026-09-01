package com.urlshortener.service.api;

import com.urlshortener.service.api.dto.CreateUrlRequest;
import com.urlshortener.service.api.dto.UrlResponse;
import com.urlshortener.service.domain.ShortUrl;
import com.urlshortener.service.service.QrCodeRenderer;
import com.urlshortener.service.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls")
@RequiredArgsConstructor
public class UrlController {

    private final UrlShortenerService service;
    private final QrCodeRenderer qrCodeRenderer;

    @Value("${app.base-url}")
    private String baseUrl;

    @Operation(summary = "Shorten a URL")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UrlResponse create(@RequestBody CreateUrlRequest request) {
        ShortUrl entity = service.create(request.longUrl(), request.expiresAt(), request.customAlias());
        return UrlResponse.from(entity, baseUrl);
    }

    @Operation(summary = "Get metadata for a short code without redirecting")
    @GetMapping("/{code}")
    public UrlResponse get(@PathVariable String code) {
        ShortUrl entity = service.resolve(code);
        return UrlResponse.from(entity, baseUrl);
    }

    @Operation(summary = "PNG QR code encoding the short URL for a code")
    @GetMapping(value = "/{code}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable String code) {
        ShortUrl entity = service.resolveUnexpired(code);
        byte[] png = qrCodeRenderer.pngFor(baseUrl + "/" + entity.getShortCode());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }
}
