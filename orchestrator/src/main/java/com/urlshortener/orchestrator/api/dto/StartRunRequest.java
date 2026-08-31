package com.urlshortener.orchestrator.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record StartRunRequest(
        @NotBlank String workflowDefinitionId,
        Map<String, String> initialContext,
        @NotBlank String createdBy,
        /** When true, non-manual nodes are executed by their NodeExecutor instead of waiting for a callback. */
        boolean autonomous
) {
}
