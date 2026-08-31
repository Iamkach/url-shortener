package com.urlshortener.orchestrator.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a worker's raw textual reply into a {@link NodeExecutionResult}. Shared verbatim by the
 * {@code llm} and {@code agent} executors so the "what does a node's reply look like" contract lives
 * in exactly one place.
 *
 * <p>The contract: the reply contains, somewhere, a JSON object
 * {@code {"status":"complete"|"fail","artifacts":{"<key>":"<value>"},"notes":"<short why>"}}.
 * Prose or {@code ```json} fences around it are tolerated. Anything that doesn't parse, or carries an
 * unknown status, becomes {@code fail(...)} — never a thrown exception — so the engine's
 * retry/fallback/rollback ladder stays in charge.
 */
public final class NodeResultParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private NodeResultParser() {
    }

    public static NodeExecutionResult parse(String raw) {
        String body = extractJsonObject(raw);
        if (body == null) {
            return NodeExecutionResult.fail("unparseable worker response (no JSON object): " + truncate(raw));
        }
        try {
            JsonNode root = JSON.readTree(body);
            String status = root.path("status").asText("");
            String notes = root.path("notes").asText("");
            if ("fail".equalsIgnoreCase(status)) {
                return NodeExecutionResult.fail(notes.isBlank() ? "worker reported failure" : notes);
            }
            if (!"complete".equalsIgnoreCase(status)) {
                return NodeExecutionResult.fail("worker response has unknown status '" + status + "'");
            }
            Map<String, String> artifacts = new LinkedHashMap<>();
            root.path("artifacts").fields()
                    .forEachRemaining(e -> artifacts.put(e.getKey(), e.getValue().asText()));
            return NodeExecutionResult.complete(artifacts, notes);
        } catch (Exception e) {
            return NodeExecutionResult.fail("unparseable worker response: " + e.getMessage());
        }
    }

    /** Tolerates prose or {@code ```json} fences around the object. */
    static String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : null;
    }

    private static String truncate(String s) {
        s = s == null ? "" : s.strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
