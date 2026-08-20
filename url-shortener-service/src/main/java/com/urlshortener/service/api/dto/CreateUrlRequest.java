package com.urlshortener.service.api.dto;

import java.time.Instant;

public record CreateUrlRequest(
        String longUrl,
        Instant expiresAt
) {
}
