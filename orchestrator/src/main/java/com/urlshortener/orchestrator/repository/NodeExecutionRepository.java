package com.urlshortener.orchestrator.repository;

import com.urlshortener.orchestrator.domain.NodeExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NodeExecutionRepository extends JpaRepository<NodeExecutionEntity, String> {

    List<NodeExecutionEntity> findByRunId(String runId);

    Optional<NodeExecutionEntity> findByRunIdAndNodeId(String runId, String nodeId);
}
