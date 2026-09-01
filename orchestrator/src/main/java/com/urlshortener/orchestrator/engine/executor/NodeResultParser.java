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

    /**
     * Tolerates prose or {@code ```json} fences around the object. Returns the <em>last</em>
     * balanced {@code {…}} object in the text: workers are told to "finish by printing ONLY the
     * JSON object", but their preamble often contains stray braces (a {@code {code}} path segment,
     * a {@code ${…}} snippet), so a naive first-{@code &#123;}/last-{@code &#125;} span does not parse.
     * Brace counting is string- and escape-aware. Falls back to the outermost span.
     */
    static String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int end = raw.lastIndexOf('}');
        while (end >= 0) {
            int start = matchingOpenBrace(raw, end);
            if (start >= 0) {
                return raw.substring(start, end + 1);
            }
            end = raw.lastIndexOf('}', end - 1);
        }
        int first = raw.indexOf('{');
        int last = raw.lastIndexOf('}');
        return first >= 0 && last > first ? raw.substring(first, last + 1) : null;
    }

    /** Index of the {@code &#123;} that balances the {@code &#125;} at {@code closeIdx}, or -1. */
    private static int matchingOpenBrace(String s, int closeIdx) {
        int depth = 0;
        boolean inString = false;
        for (int i = closeIdx; i >= 0; i--) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '"' && !isEscaped(s, i)) {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isEscaped(String s, int quoteIdx) {
        int backslashes = 0;
        for (int i = quoteIdx - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static String truncate(String s) {
        s = s == null ? "" : s.strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
