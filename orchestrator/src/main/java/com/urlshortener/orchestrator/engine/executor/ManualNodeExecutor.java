package com.urlshortener.orchestrator.engine.executor;

import org.springframework.stereotype.Component;

/**
 * The default executor and the one the whole deterministic test suite is built around: it does
 * nothing. The node stays {@code RUNNING} and the engine waits for an external
 * {@code complete}/{@code fail}/{@code approve}/{@code reject} REST callback, exactly as before the
 * executor seam existed. {@link NodeDispatchListener} short-circuits on this id and never calls
 * {@link #execute}.
 */
@Component
public class ManualNodeExecutor implements NodeExecutor {

    public static final String ID = "manual";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        throw new UnsupportedOperationException("ManualNodeExecutor does no work; the engine waits for a REST callback");
    }
}
