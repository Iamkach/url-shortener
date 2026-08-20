package com.urlshortener.orchestrator.api.dto;

import jakarta.validation.constraints.NotBlank;

public record InvalidateRequest(
        @NotBlank String rationale,
        String actor
) {
}
