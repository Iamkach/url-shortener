package com.urlshortener.orchestrator.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Persisted runtime state of one execution of a {@link com.urlshortener.orchestrator.definition.WorkflowDefinition}.
 * The definition itself is static config (YAML); this entity plus {@link NodeExecutionEntity}
 * and {@link AuditEventEntity} form the audit-grade, queryable runtime record.
 */
@Entity
@Table(name = "workflow_run")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowRunEntity {

    @Id
    private String id;

    private String workflowDefinitionId;

    @Enumerated(EnumType.STRING)
    private RunStatus status;

    private Instant startedAt;
    private Instant completedAt;
    private String createdBy;

    /**
     * Decision-lineage context shared across stages. Namespaced as {@code nodeId.artifactKey}
     * when a node completes, so downstream gates can reference upstream outputs unambiguously.
     */
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "workflow_run_context", joinColumns = @jakarta.persistence.JoinColumn(name = "run_id"))
    @MapKeyColumn(name = "ctx_key")
    @Column(name = "ctx_value", length = 4000)
    private Map<String, String> context = new HashMap<>();

    @Version
    private Long version;

    public WorkflowRunEntity(String id, String workflowDefinitionId, String createdBy, Map<String, String> initialContext) {
        this.id = id;
        this.workflowDefinitionId = workflowDefinitionId;
        this.createdBy = createdBy;
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
        if (initialContext != null) {
            this.context.putAll(initialContext);
        }
    }
}
