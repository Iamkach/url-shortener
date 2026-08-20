package com.urlshortener.orchestrator.repository;

import com.urlshortener.orchestrator.domain.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, String> {

    List<AuditEventEntity> findByRunIdOrderByTimestampAsc(String runId);

    List<AuditEventEntity> findByRunIdAndEventTypeOrderByTimestampAsc(String runId, com.urlshortener.orchestrator.domain.EventType eventType);
}
