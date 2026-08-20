package com.urlshortener.orchestrator.domain;

public enum NodeStatus {
    PENDING,
    READY,
    RUNNING,
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    RETRYING,
    ROLLED_BACK,
    SKIPPED,
    STALE
}
