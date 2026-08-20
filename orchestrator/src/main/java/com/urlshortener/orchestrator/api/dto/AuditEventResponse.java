package com.urlshortener.orchestrator.api.dto;

import com.urlshortener.orchestrator.domain.Actor;
import com.urlshortener.orchestrator.domain.AuditEventEntity;
import com.urlshortener.orchestrator.domain.EventType;

import java.time.Instant;

public record AuditEventResponse(
        String id,
        String nodeId,
        Instant timestamp,
        Actor actor,
        EventType eventType,
        String message,
        String rationale
) {
    public static AuditEventResponse from(AuditEventEntity e) {
        return new AuditEventResponse(e.getId(), e.getNodeId(), e.getTimestamp(), e.getActor(),
                e.getEventType(), e.getMessage(), e.getRationale());
    }
}
