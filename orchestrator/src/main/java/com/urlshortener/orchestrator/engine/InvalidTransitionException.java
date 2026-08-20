package com.urlshortener.orchestrator.engine;

/** Thrown when an API caller attempts a state transition that isn't valid from the node/run's current status. */
public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(String message) {
        super(message);
    }
}
