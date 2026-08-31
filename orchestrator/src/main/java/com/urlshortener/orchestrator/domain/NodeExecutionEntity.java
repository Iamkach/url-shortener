package com.urlshortener.orchestrator.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "node_execution", indexes = @Index(name = "idx_node_exec_run", columnList = "runId"))
@Getter
@Setter
@NoArgsConstructor
public class NodeExecutionEntity {

    @Id
    private String id;

    private String runId;
    private String nodeId;

    @Enumerated(EnumType.STRING)
    private NodeStatus status;

    private int attempt;
    private Instant startedAt;
    private Instant endedAt;

    @Column(length = 2000)
    private String lastError;

    /** Output artifacts produced by this node (e.g. specPath, testReport, commit). */
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "node_execution_artifacts", joinColumns = @jakarta.persistence.JoinColumn(name = "node_execution_id"))
    @MapKeyColumn(name = "artifact_key")
    @Column(name = "artifact_value", length = 4000)
    private Map<String, String> artifacts = new HashMap<>();

    @Version
    private Long version;

    public NodeExecutionEntity(String id, String runId, String nodeId) {
        this.id = id;
        this.runId = runId;
        this.nodeId = nodeId;
        this.status = NodeStatus.PENDING;
        this.attempt = 0;
    }
}
