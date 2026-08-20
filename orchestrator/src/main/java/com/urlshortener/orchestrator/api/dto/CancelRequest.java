package com.urlshortener.orchestrator.api.dto;

public record CancelRequest(
        boolean rollback,
        String actor
) {
}
