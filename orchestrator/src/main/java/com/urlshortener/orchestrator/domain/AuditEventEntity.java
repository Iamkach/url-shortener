package com.urlshortener.orchestrator.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only audit trail entry. Every state transition, approval, retry, rollback and
 * replan decision is recorded here, giving audit-grade observability and decision lineage.
 */
@Entity
@Table(name = "audit_event", indexes = @Index(name = "idx_audit_run", columnList = "runId"))
@Getter
@Setter
@NoArgsConstructor
public class AuditEventEntity {

    @Id
    private String id;

    private String runId;
    private String nodeId;
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    private Actor actor;

    @Enumerated(EnumType.STRING)
    private EventType eventType;

    private String message;

    @jakarta.persistence.Column(length = 2000)
    private String rationale;

    public AuditEventEntity(String id, String runId, String nodeId, Actor actor, EventType eventType, String message, String rationale) {
        this.id = id;
        this.runId = runId;
        this.nodeId = nodeId;
        this.actor = actor;
        this.eventType = eventType;
        this.message = message;
        this.rationale = rationale;
        this.timestamp = Instant.now();
    }
}
