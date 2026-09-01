package com.urlshortener.orchestrator.engine.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Real subprocess execution for the {@code agent} executor. Loaded only when
 * {@code orchestrator.executor.mode=agent}, so the default/test boot never carries it.
 *
 * <p>stdout and stderr are drained on separate threads (a full pipe buffer would otherwise deadlock
 * the child); on timeout the process tree is force-killed and {@code timedOut=true} is returned.
 */
@Component
@ConditionalOnProperty(prefix = "orchestrator.executor", name = "mode", havingValue = "agent")
@Slf4j
public class DefaultProcessRunner implements ProcessRunner {

    @Override
    public Outcome run(List<String> command, Path workingDir, Map<String, String> env,
                       String stdin, Duration timeout) {
        log.info("agent process: {} (cwd={}, timeout={}s)", command, workingDir, timeout.toSeconds());
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.environment().putAll(env);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Outcome(-1, "", "failed to start '" + command.get(0) + "': " + e.getMessage(), false);
        }

        // Start the drains before touching stdin: a child that writes more than the OS pipe buffer
        // (~64 KB) before consuming all of stdin would otherwise deadlock against the write below.
        CompletableFuture<String> out = readStream(process.getInputStream());
        CompletableFuture<String> err = readStream(process.getErrorStream());

        if (stdin != null) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("could not write agent prompt to stdin: {}", e.getMessage());
            }
        } else {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // child may have already exited
            }
        }

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Outcome(-1, out.join(), "interrupted", true);
        }

        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return new Outcome(-1, safeJoin(out), safeJoin(err), true);
        }
        return new Outcome(process.exitValue(), safeJoin(out), safeJoin(err), false);
    }

    private static CompletableFuture<String> readStream(java.io.InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String safeJoin(CompletableFuture<String> f) {
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }
}
