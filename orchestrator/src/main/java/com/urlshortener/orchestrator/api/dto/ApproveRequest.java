package com.urlshortener.orchestrator.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record ApproveRequest(
        @NotBlank String approver,
        String rationale,
        Map<String, String> artifacts
) {
}
