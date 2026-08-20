package com.urlshortener.orchestrator.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record StartRunRequest(
        @NotBlank String workflowDefinitionId,
        Map<String, String> initialContext,
        @NotBlank String createdBy
) {
}
