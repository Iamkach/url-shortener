package com.urlshortener.orchestrator.engine.executor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Seam over {@link ProcessBuilder} so {@link ClaudeCliAgentPort} can be tested without spawning a
 * real process. The production implementation is {@link DefaultProcessRunner}; tests pass a fake.
 */
public interface ProcessRunner {

    Outcome run(List<String> command, Path workingDir, Map<String, String> env, String stdin, Duration timeout);

    record Outcome(int exitCode, String stdout, String stderr, boolean timedOut) {
    }
}
