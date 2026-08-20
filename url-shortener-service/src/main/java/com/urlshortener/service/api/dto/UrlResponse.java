package com.urlshortener.service.api.dto;

import com.urlshortener.service.domain.ShortUrl;

import java.time.Instant;

public record UrlResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt
) {
    public static UrlResponse from(ShortUrl entity, String baseUrl) {
        return new UrlResponse(entity.getShortCode(), baseUrl + "/" + entity.getShortCode(),
                entity.getLongUrl(), entity.getCreatedAt(), entity.getExpiresAt());
    }
}
