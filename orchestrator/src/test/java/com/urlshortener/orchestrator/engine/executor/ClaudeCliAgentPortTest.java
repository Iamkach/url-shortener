package com.urlshortener.orchestrator.engine.executor;

import com.urlshortener.orchestrator.domain.StageType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClaudeCliAgentPort} against a fake {@link ProcessRunner} — asserts the command line, env,
 * and stdin/arg wiring without ever spawning a process.
 */
class ClaudeCliAgentPortTest {

    /** Captures whatever the port hands the runner. */
    private static final class CapturingRunner implements ProcessRunner {
        List<String> command;
        Path workingDir;
        Map<String, String> env;
        String stdin;
        Duration timeout;
        Outcome outcome = new Outcome(0, "{\"result\":\"{\\\"status\\\":\\\"complete\\\"}\"}", "", false);

        @Override
        public Outcome run(List<String> command, Path workingDir, Map<String, String> env,
                           String stdin, Duration timeout) {
            this.command = command;
            this.workingDir = workingDir;
            this.env = env;
            this.stdin = stdin;
            this.timeout = timeout;
            return outcome;
        }
    }

    private AgentInvocationTask task() {
        return new AgentInvocationTask(
                "run-42", "implementation", StageType.IMPLEMENTATION, "do the work",
                List.of("Read", "Edit", "Bash"),
                List.of("url-shortener-service/src/**", "specs/**/tasks.md"),
                Path.of("/repo"), Duration.ofSeconds(120));
    }

    private ExecutorProperties props() {
        ExecutorProperties p = new ExecutorProperties();
        p.setMode("agent");
        p.getAgent().setCommand("claude");
        p.getAgent().setArgsTemplate("-p --output-format json --add-dir {repoDir} --allowedTools {allowedTools}");
        return p;
    }

    @Test
    void rendersArgTemplateWithRepoDirAndAllowedTools() {
        CapturingRunner runner = new CapturingRunner();
        new ClaudeCliAgentPort(props(), runner).invoke(task());

        assertThat(runner.command).containsExactly(
                "claude", "-p", "--output-format", "json",
                "--add-dir", Path.of("/repo").toString(),
                "--allowedTools", "Read,Edit,Bash");
        assertThat(runner.workingDir).isEqualTo(Path.of("/repo"));
        assertThat(runner.timeout).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void exportsGovernanceEnvVars() {
        CapturingRunner runner = new CapturingRunner();
        new ClaudeCliAgentPort(props(), runner).invoke(task());

        assertThat(runner.env)
                .containsEntry("ORCH_RUN_ID", "run-42")
                .containsEntry("ORCH_NODE_ID", "implementation")
                .containsEntry("ORCH_NODE_STAGE", "IMPLEMENTATION")
                .containsEntry("ORCH_ALLOW_PATHS", "url-shortener-service/src/**,specs/**/tasks.md");
    }

    @Test
    void promptGoesToStdinByDefault() {
        CapturingRunner runner = new CapturingRunner();
        new ClaudeCliAgentPort(props(), runner).invoke(task());

        assertThat(runner.stdin).isEqualTo("do the work");
        assertThat(runner.command).doesNotContain("do the work");
    }

    @Test
    void promptGoesToTrailingArgWhenPromptViaIsArg() {
        ExecutorProperties p = props();
        p.getAgent().setPromptVia("arg");
        CapturingRunner runner = new CapturingRunner();
        new ClaudeCliAgentPort(p, runner).invoke(task());

        assertThat(runner.stdin).isNull();
        assertThat(runner.command).endsWith("do the work");
    }

    @Test
    void mapsTimedOutAndExitCodeStraightThrough() {
        CapturingRunner runner = new CapturingRunner();
        runner.outcome = new ProcessRunner.Outcome(137, "", "killed", true);
        AgentInvocationResult r = new ClaudeCliAgentPort(props(), runner).invoke(task());

        assertThat(r.timedOut()).isTrue();
        assertThat(r.exitCode()).isEqualTo(137);
        assertThat(r.stderr()).isEqualTo("killed");
    }
}
