package com.urlshortener.orchestrator.api.dto;

import com.urlshortener.orchestrator.definition.WorkflowDefinition;

import java.util.List;

public record WorkflowSummaryResponse(
        String id,
        String name,
        List<String> nodeIds
) {
    public static WorkflowSummaryResponse from(WorkflowDefinition def) {
        return new WorkflowSummaryResponse(def.getId(), def.getName(),
                def.getNodes().stream().map(com.urlshortener.orchestrator.definition.NodeDefinition::getId).toList());
    }
}
