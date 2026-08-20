package com.urlshortener.orchestrator.api;

import com.urlshortener.orchestrator.api.dto.WorkflowSummaryResponse;
import com.urlshortener.orchestrator.definition.WorkflowDefinitionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowDefinitionRegistry registry;

    @GetMapping
    public List<WorkflowSummaryResponse> list() {
        return registry.all().values().stream().map(WorkflowSummaryResponse::from).toList();
    }
}
