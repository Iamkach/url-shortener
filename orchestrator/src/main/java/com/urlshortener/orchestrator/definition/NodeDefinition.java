package com.urlshortener.orchestrator.definition;

import com.urlshortener.orchestrator.domain.StageType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Static definition of one node (SDLC stage) in a {@link WorkflowDefinition}'s DAG.
 * Loaded from YAML; immutable at runtime.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeDefinition {

    private String id;
    private StageType stage;
    private List<String> dependsOn = new ArrayList<>();
    private boolean requiresApproval = false;
    private int maxRetries = 0;
    private String fallbackNodeId;
    private boolean compensation = false;

    /**
     * Which {@code NodeExecutor} does this node's work: {@code manual} (default — engine waits for a
     * REST callback), {@code scripted}, {@code llm}, or {@code agent}. Null/blank means "use the
     * global {@code orchestrator.executor.mode}". Validated in {@link WorkflowDefinition#validate()}.
     */
    private String executor;

    /** Gate expression evaluated before the node may start running, e.g. "requireContext:design.designPath". */
    private String entryGate;

    /** Gate expression evaluated before the node may be marked COMPLETED, e.g. "requireArtifact:testReport". */
    private String exitGate;
}
