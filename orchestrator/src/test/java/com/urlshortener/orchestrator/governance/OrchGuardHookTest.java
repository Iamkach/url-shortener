package com.urlshortener.orchestrator.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shells the real {@code .claude/hooks/orch_guard.py} with crafted PreToolUse stdin + env and
 * asserts its allow/deny decision. Skips (does not fail) when no {@code python} is on the box.
 */
class OrchGuardHookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path repoRoot;
    private static Path script;
    private static String python;

    @BeforeAll
    static void locate() {
        repoRoot = Path.of("..").toAbsolutePath().normalize();
        script = repoRoot.resolve(".claude/hooks/orch_guard.py");
        assertThat(Files.exists(script)).as("hook script present").isTrue();
        python = firstWorking("python", "python3");
        assumeTrue(python != null, "no python interpreter available");
    }

    private static String firstWorking(String... candidates) {
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "--version").redirectErrorStream(true).start();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return c;
                }
            } catch (IOException | InterruptedException ignored) {
                // try next
            }
        }
        return null;
    }

    private String decisionFor(String filePath, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(python, script.toString());
        pb.directory(repoRoot.toFile());
        pb.environment().remove("ORCH_RUN_ID");
        pb.environment().remove("ORCH_ALLOW_PATHS");
        pb.environment().remove("ORCH_NODE_STAGE");
        pb.environment().putAll(env);
        Process p = pb.start();
        String stdin = "{\"tool_input\":{\"file_path\":\"" + filePath + "\"}}";
        try (OutputStream os = p.getOutputStream()) {
            os.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(10, TimeUnit.SECONDS);
        JsonNode root = JSON.readTree(out);
        return root.path("hookSpecificOutput").path("permissionDecision").asText();
    }

    @Test
    void deniesProductCodeWhenNoRunIdInSession() throws Exception {
        assertThat(decisionFor("url-shortener-service/src/main/java/Foo.java", Map.of()))
                .isEqualTo("deny");
        assertThat(decisionFor("orchestrator/src/main/java/Bar.java", Map.of())).isEqualTo("deny");
        assertThat(decisionFor("specs/004-autonomous-agent/plan.md", Map.of())).isEqualTo("deny");
    }

    @Test
    void alwaysAllowsDocsAndMeta() throws Exception {
        assertThat(decisionFor("CLAUDE.md", Map.of())).isEqualTo("allow");
        assertThat(decisionFor("README.md", Map.of())).isEqualTo("allow");
        assertThat(decisionFor(".gitignore", Map.of())).isEqualTo("allow");
        assertThat(decisionFor("docs/architecture.md", Map.of())).isEqualTo("allow");
        // even mid-run with a narrow stage
        assertThat(decisionFor("CLAUDE.md", Map.of(
                "ORCH_RUN_ID", "r1", "ORCH_NODE_STAGE", "TESTING",
                "ORCH_ALLOW_PATHS", "url-shortener-service/src/test/**"))).isEqualTo("allow");
    }

    @Test
    void inARunDeniesPathOutsideTheStageGlobs() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("ORCH_RUN_ID", "r1");
        env.put("ORCH_NODE_STAGE", "TESTING");
        env.put("ORCH_ALLOW_PATHS", "url-shortener-service/src/test/**,docs/scenario-runs/**");
        assertThat(decisionFor("url-shortener-service/src/main/java/Foo.java", env)).isEqualTo("deny");
    }

    @Test
    void inARunAllowsPathInsideTheStageGlobs() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("ORCH_RUN_ID", "r1");
        env.put("ORCH_NODE_STAGE", "TESTING");
        env.put("ORCH_ALLOW_PATHS", "url-shortener-service/src/test/**,docs/scenario-runs/**");
        assertThat(decisionFor("url-shortener-service/src/test/java/BarTest.java", env)).isEqualTo("allow");
        assertThat(decisionFor("docs/scenario-runs/004-autonomous-agent.json", env)).isEqualTo("allow");
    }
}
