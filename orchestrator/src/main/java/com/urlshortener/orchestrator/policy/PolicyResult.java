package com.urlshortener.orchestrator.policy;

public record PolicyResult(boolean allowed, String reason) {

    public static PolicyResult allow() {
        return new PolicyResult(true, null);
    }

    public static PolicyResult deny(String reason) {
        return new PolicyResult(false, reason);
    }
}
