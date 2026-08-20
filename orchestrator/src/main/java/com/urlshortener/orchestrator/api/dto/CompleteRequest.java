package com.urlshortener.orchestrator.api.dto;

import java.util.Map;

public record CompleteRequest(
        Map<String, String> artifacts,
        String actor
) {
}
