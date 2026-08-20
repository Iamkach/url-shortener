package com.urlshortener.orchestrator.api.dto;

import jakarta.validation.constraints.NotBlank;

public record FailRequest(
        @NotBlank String reason,
        String actor
) {
}
