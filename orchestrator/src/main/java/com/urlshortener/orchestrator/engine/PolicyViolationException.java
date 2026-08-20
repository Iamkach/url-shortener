package com.urlshortener.orchestrator.engine;

/** Thrown when an entry or exit gate rejects a node transition. */
public class PolicyViolationException extends RuntimeException {
    public PolicyViolationException(String message) {
        super(message);
    }
}
