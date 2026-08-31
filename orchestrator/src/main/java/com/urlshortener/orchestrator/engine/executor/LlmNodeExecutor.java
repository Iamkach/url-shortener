package com.urlshortener.orchestrator.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.orchestrator.domain.StageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a node's SDLC work by asking a model. Loaded only when {@code orchestrator.executor.mode=llm}.
 *
 * <p>Guardrails, all of which degrade to a normal {@code fail(...)} so the engine's retry/rollback
 * ladder stays in charge: a per-run model-call budget, a lenient JSON parse (a malformed reply is a
 * failure, not a thrown exception), and an explicit {@code "status":"fail"} the model can return.
 * The returned artifacts still pass through the node's exit gate in {@code WorkflowEngine.complete} —
 * a model that forgets a required artifact is stopped by governance exactly like a human would be.
 */
@Component
@ConditionalOnProperty(prefix = "orchestrator.executor", name = "mode", havingValue = "llm")
@Slf4j
public class LlmNodeExecutor implements NodeExecutor {

    public static final String ID = "llm";

    private final ChatPort chat;
    private final ExecutorProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Integer> callsPerRun = new ConcurrentHashMap<>();

    public LlmNodeExecutor(ChatPort chat, ExecutorProperties properties) {
        this.chat = chat;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        int budget = properties.getLlm().getMaxModelCallsPerRun();
        int used = callsPerRun.merge(request.runId(), 1, Integer::sum);
        if (used > budget) {
            return NodeExecutionResult.fail("model-call budget exhausted for run (" + used + " > " + budget + ")");
        }

        String raw;
        try {
            raw = chat.complete(systemPrompt(request.node().getStage()), userPrompt(request),
                    properties.getLlm().getMaxOutputTokens());
        } catch (RuntimeException e) {
            return NodeExecutionResult.fail("model call failed: " + e.getMessage());
        }
        return parse(raw);
    }

    private NodeExecutionResult parse(String raw) {
        String body = extractJsonObject(raw);
        if (body == null) {
            return NodeExecutionResult.fail("unparseable model response (no JSON object): " + truncate(raw));
        }
        try {
            JsonNode root = json.readTree(body);
            String status = root.path("status").asText("");
            String notes = root.path("notes").asText("");
            if ("fail".equalsIgnoreCase(status)) {
                return NodeExecutionResult.fail(notes.isBlank() ? "model reported failure" : notes);
            }
            if (!"complete".equalsIgnoreCase(status)) {
                return NodeExecutionResult.fail("model response has unknown status '" + status + "'");
            }
            Map<String, String> artifacts = new LinkedHashMap<>();
            JsonNode artifactsNode = root.path("artifacts");
            artifactsNode.fields().forEachRemaining(e -> artifacts.put(e.getKey(), e.getValue().asText()));
            return NodeExecutionResult.complete(artifacts, notes);
        } catch (Exception e) {
            return NodeExecutionResult.fail("unparseable model response: " + e.getMessage());
        }
    }

    /** Tolerates prose or ```json fences around the object. */
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

    private static String systemPrompt(StageType stage) {
        String role = switch (stage) {
            case REQUIREMENTS -> "You normalize a raw ask into a written spec.";
            case DESIGN -> "You turn a spec into a technical design/plan.";
            case IMPLEMENTATION -> "You describe the code change and produce a commit reference.";
            case TESTING -> "You describe the tests run and produce a test report reference.";
            case DOCUMENTATION -> "You produce user/developer documentation.";
            case RELEASE_READINESS -> "You summarize release readiness.";
        };
        return role + " You are one stage of a governed SDLC pipeline; the engine enforces gates, "
                + "you only do this stage's work.\n"
                + "Respond with ONLY a JSON object, no prose, no code fences:\n"
                + "{\"status\":\"complete\"|\"fail\",\"artifacts\":{\"<key>\":\"<value>\"},\"notes\":\"<short why>\"}\n"
                + "The artifacts map MUST contain the key named in the exit gate (e.g. exit gate "
                + "'requireArtifact:commit' => artifacts must have \"commit\").";
    }

    private String userPrompt(NodeExecutionRequest request) {
        String context;
        try {
            context = json.writerWithDefaultPrettyPrinter().writeValueAsString(request.context());
        } catch (Exception e) {
            context = String.valueOf(request.context());
        }
        return "Node: " + request.node().getId() + " (stage " + request.node().getStage() + ")\n"
                + "Entry gate: " + orNone(request.node().getEntryGate()) + "\n"
                + "Exit gate: " + orNone(request.node().getExitGate()) + "\n"
                + "Accumulated pipeline context (namespaced nodeId.artifactKey):\n" + context + "\n\n"
                + "Do this stage's work and return the JSON object.";
    }

    private static String orNone(String s) {
        return s == null || s.isBlank() ? "(none)" : s;
    }
}
