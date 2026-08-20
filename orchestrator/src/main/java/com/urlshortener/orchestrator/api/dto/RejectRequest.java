package com.urlshortener.orchestrator.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectRequest(
        @NotBlank String approver,
        @NotBlank String rationale
) {
}
