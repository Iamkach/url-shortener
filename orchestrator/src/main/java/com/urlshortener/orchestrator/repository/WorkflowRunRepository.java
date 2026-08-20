package com.urlshortener.orchestrator.repository;

import com.urlshortener.orchestrator.domain.WorkflowRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, String> {
}
