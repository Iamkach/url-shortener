package com.urlshortener.orchestrator.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.orchestrator.domain.StageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs a node's SDLC work by spawning an agent CLI (Claude Code by default) that does <em>real</em>
 * engineering — reads the repo, edits files, runs {@code mvn test}, commits — then prints a single
 * result JSON object. Loaded only when {@code orchestrator.executor.mode=agent}.
 *
 * <p>Same guardrail philosophy as {@link LlmNodeExecutor}: a per-run call budget, and every failure
 * mode (non-zero exit, timeout, thrown exception, unparseable output) degrades to a plain
 * {@code fail(...)} so the engine's retry / fallback / rollback ladder — and the node's exit gate in
 * {@code WorkflowEngine.complete} — stay fully in charge. The executor never touches governance.
 */
@Component
@ConditionalOnProperty(prefix = "orchestrator.executor", name = "mode", havingValue = "agent")
@Slf4j
public class AgentNodeExecutor implements NodeExecutor {

    public static final String ID = "agent";

    private static final List<String> BUILD_TOOLS = List.of("Read", "Write", "Edit", "Bash", "Glob", "Grep");
    private static final List<String> DOC_TOOLS = List.of("Read", "Write", "Edit", "Glob", "Grep");
    private static final List<String> READONLY_TOOLS = List.of("Read", "Glob", "Grep");

    private final AgentInvocationPort agent;
    private final ExecutorProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private static final int MAX_TRACKED_RUNS = 10_000;
    // Bounded (self-evicting) to avoid unbounded growth for long-lived processes (finding #3).
    private final Map<String, Integer> callsPerRun = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_TRACKED_RUNS;
                }
            });

    public AgentNodeExecutor(AgentInvocationPort agent, ExecutorProperties properties) {
        this.agent = agent;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        ExecutorProperties.Agent cfg = properties.getAgent();
        int budget = cfg.getMaxAgentCallsPerRun();
        int used = callsPerRun.merge(request.runId(), 1, Integer::sum);
        if (used > budget) {
            return NodeExecutionResult.fail("agent-call budget exhausted for run (" + used + " > " + budget + ")");
        }

        StageType stage = request.node().getStage();
        AgentInvocationTask task = new AgentInvocationTask(
                request.runId(),
                request.node().getId(),
                stage,
                buildPrompt(request),
                allowedTools(stage),
                allowedPaths(stage),
                Path.of(cfg.getWorkingDir()).toAbsolutePath().normalize(),
                Duration.ofSeconds(cfg.getTimeoutSeconds()));

        AgentInvocationResult result;
        try {
            result = agent.invoke(task);
        } catch (RuntimeException e) {
            return NodeExecutionResult.fail("agent invocation failed: " + e.getMessage());
        }

        if (result.timedOut()) {
            return NodeExecutionResult.fail("agent timed out after " + cfg.getTimeoutSeconds() + "s");
        }
        if (result.exitCode() != 0) {
            return NodeExecutionResult.fail(
                    "agent exited " + result.exitCode() + ": " + truncate(result.stderr()));
        }
        return NodeResultParser.parse(finalMessage(result.stdout()));
    }

    /** The stage's write globs from {@code orchestrator.executor.agent.stage-paths}. */
    List<String> allowedPaths(StageType stage) {
        String csv = properties.getAgent().getStagePaths().getOrDefault(stage.name(), "");
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    List<String> allowedTools(StageType stage) {
        return switch (stage) {
            case DESIGN, IMPLEMENTATION, TESTING -> BUILD_TOOLS;
            case DOCUMENTATION -> DOC_TOOLS;
            case REQUIREMENTS, RELEASE_READINESS -> READONLY_TOOLS;
        };
    }

    /**
     * Claude Code's {@code --output-format json} wraps the run in
     * {@code {"type":"result","result":"<final assistant text>", …}}. Pull that text out; if stdout
     * isn't that envelope (another CLI, or plain text), hand it through as-is and let
     * {@link NodeResultParser} find the object.
     */
    private String finalMessage(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return "";
        }
        try {
            JsonNode root = json.readTree(stdout.trim());
            JsonNode resultNode = root.path("result");
            if (resultNode.isTextual() && !resultNode.asText().isBlank()) {
                return resultNode.asText();
            }
        } catch (Exception ignored) {
            // not the Claude envelope — fall through
        }
        return stdout;
    }

    private static String truncate(String s) {
        s = s == null ? "" : s.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private String buildPrompt(NodeExecutionRequest request) {
        StageType stage = request.node().getStage();
        String context;
        try {
            context = json.writerWithDefaultPrettyPrinter().writeValueAsString(request.context());
        } catch (Exception e) {
            context = String.valueOf(request.context());
        }
        return work(stage) + "\n\n"
                + "You are one stage of a governed SDLC pipeline building the QR-code feature "
                + "(spec: specs/004-autonomous-agent/). The engine enforces gates and governance; "
                + "you only do THIS stage's work, in the working tree, on the current git branch.\n"
                + "Node: " + request.node().getId() + " (stage " + stage + ")\n"
                + "Entry gate: " + orNone(request.node().getEntryGate()) + "\n"
                + "Exit gate: " + orNone(request.node().getExitGate()) + "\n"
                + "Accumulated pipeline context (namespaced nodeId.artifactKey):\n" + context + "\n\n"
                + "Finish by printing ONLY a JSON object, no prose, no code fences:\n"
                + "{\"status\":\"complete\"|\"fail\",\"artifacts\":{\"<key>\":\"<value>\"},\"notes\":\"<short why>\"}\n"
                + "The artifacts map MUST contain the key named in the exit gate above.";
    }

    private static String work(StageType stage) {
        return switch (stage) {
            case REQUIREMENTS -> "Normalize the raw ask into specs/004-autonomous-agent/spec.md.";
            case DESIGN -> "Read specs/004-autonomous-agent/spec.md. Write the technical design into "
                    + "specs/004-autonomous-agent/plan.md and break it into specs/004-autonomous-agent/tasks.md. "
                    + "Set artifacts.designPath to the plan file path.";
            case IMPLEMENTATION -> "Read specs/004-autonomous-agent/plan.md + tasks.md. Make the code changes under "
                    + "url-shortener-service/src/main. Run `mvn -pl url-shortener-service test` until green. "
                    + "`git add` + `git commit` on the current branch. Set artifacts.commit to `git rev-parse HEAD`.";
            case TESTING -> "Add tests under url-shortener-service/src/test covering the new endpoint. Run "
                    + "`mvn -pl url-shortener-service test` until green. Write a short report file under "
                    + "docs/scenario-runs/ and set artifacts.testReport to its path.";
            case DOCUMENTATION -> "Document the new endpoint in docs/ and README.md. Set artifacts.docsPath to "
                    + "the file you wrote.";
            case RELEASE_READINESS -> "Summarize release readiness from the accumulated context.";
        };
    }

    private static String orNone(String s) {
        return s == null || s.isBlank() ? "(none)" : s;
    }
}
