package com.urlshortener.orchestrator.engine.executor;

import java.util.Map;

/**
 * The outcome of a {@link NodeExecutor} run. Mapped straight onto the engine's existing
 * {@code complete}/{@code fail} entry points by {@link NodeDispatchListener} — the executor never
 * bypasses the exit gate or the retry/fallback/rollback ladder.
 */
public record NodeExecutionResult(Outcome outcome, Map<String, String> artifacts, String notes) {

    public enum Outcome { COMPLETE, FAIL }

    public static NodeExecutionResult complete(Map<String, String> artifacts, String notes) {
        return new NodeExecutionResult(Outcome.COMPLETE, artifacts == null ? Map.of() : artifacts, notes);
    }

    public static NodeExecutionResult fail(String reason) {
        return new NodeExecutionResult(Outcome.FAIL, Map.of(), reason);
    }
}
