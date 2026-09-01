package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.domain.Actor;
import com.urlshortener.orchestrator.domain.EventType;
import com.urlshortener.orchestrator.domain.RunStatus;
import com.urlshortener.orchestrator.engine.executor.AgentInvocationPort;
import com.urlshortener.orchestrator.engine.executor.AgentInvocationResult;
import com.urlshortener.orchestrator.engine.executor.AgentInvocationTask;
import com.urlshortener.orchestrator.engine.executor.AgentNodeExecutor;
import com.urlshortener.orchestrator.engine.executor.ExecutorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the {@code agent} executor gets the same real DAG parallelism the seam already gives every
 * other executor: when {@code impl} completes, {@code test} and {@code docs} are dispatched together
 * and their agent invocations run <em>concurrently</em> on {@code nodeExecutorPool}. A
 * {@link CyclicBarrier} of 2 inside the fake {@link AgentInvocationPort} is the proof — if the pool
 * ran the two nodes serially, the first would never clear the barrier and its node would fail.
 *
 * <p>Also asserts governance is untouched: both human gates ({@code reqs}, {@code release}) still
 * block the autonomous run, and all four agent artifacts land in the run context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(WorkflowEngineAgentParallelTest.AgentParallelConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // release this context's pool/listener; it shares the in-mem H2 with the rest of the suite
class WorkflowEngineAgentParallelTest {

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private LatchingAgentPort agentPort;

    private String nodeStatus(String runId, String nodeId) {
        return engine.getNodes(runId).stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst().orElseThrow().getStatus().name();
    }

    @Test
    void testingAndDocumentationAgentsRunConcurrently_humanGatesStillBlock() {
        var run = engine.startRun("test-agent-parallel", Map.of(), "product-owner", true);
        String runId = run.getId();

        // Autonomy stops at the first human gate; nothing downstream has moved.
        assertThat(nodeStatus(runId, "reqs")).isEqualTo("AWAITING_APPROVAL");
        assertThat(nodeStatus(runId, "design")).isEqualTo("PENDING");

        engine.approve(runId, "reqs", "product-owner", "clear", Map.of("specPath", "specs/x/spec.md"));

        // design -> impl -> {test, docs} all run through the fake agent port, no callbacks here.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(nodeStatus(runId, "release")).isEqualTo("AWAITING_APPROVAL"));

        assertThat(agentPort.barrierTripped).isTrue();
        assertThat(agentPort.sawConcurrently).containsExactlyInAnyOrder("test", "docs");
        assertThat(nodeStatus(runId, "design")).isEqualTo("COMPLETED");
        assertThat(nodeStatus(runId, "impl")).isEqualTo("COMPLETED");
        assertThat(nodeStatus(runId, "test")).isEqualTo("COMPLETED");
        assertThat(nodeStatus(runId, "docs")).isEqualTo("COMPLETED");
        assertThat(nodeStatus(runId, "release")).isEqualTo("AWAITING_APPROVAL");

        engine.approve(runId, "release", "release-manager", "ship it", Map.of());

        var finalRun = engine.getRun(runId);
        assertThat(finalRun.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finalRun.getContext())
                .containsEntry("design.designPath", "specs/x/plan.md")
                .containsEntry("impl.commit", "abc1234")
                .containsEntry("test.testReport", "docs/scenario-runs/agent-test.json")
                .containsEntry("docs.docsPath", "docs/architecture.md");

        assertThat(engine.getAudit(runId))
                .anySatisfy(e -> assertThat(e.getActor()).isEqualTo(Actor.AGENT));

        // The agent's free-text notes (far longer than the 255-char audit `message` column) must
        // land intact on the NODE_COMPLETED event's `rationale` — never concatenated into `message`.
        // Regression guard for the audit-persist overflow that wedged the first live 004 run.
        assertThat(engine.getAudit(runId))
                .filteredOn(e -> e.getEventType() == EventType.NODE_COMPLETED
                        && "Node completed by agent".equals(e.getMessage()))
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getRationale()).isEqualTo(LatchingAgentPort.LONG_NOTES));
    }

    /** Fake port: design/impl return immediately; test + docs must meet at a 2-party barrier. */
    static class LatchingAgentPort implements AgentInvocationPort {

        /** >255 chars: overflows the audit `message` column if notes were concatenated into it. */
        static final String LONG_NOTES = "Implemented GET /api/urls/{code}/qr end to end: added QrCodeService "
                + "(ZXing QRCodeWriter -> PNG byte[]), QrController resolving the code through UrlShortenerService "
                + "with the shared soft-expire check, wired the image/png response, added ZXing core+javase to the "
                + "service pom, ran mvn -pl url-shortener-service test to green, and committed on the run branch.";

        private static final Map<String, Map<String, String>> ARTIFACTS = Map.of(
                "design", Map.of("designPath", "specs/x/plan.md"),
                "impl", Map.of("commit", "abc1234"),
                "test", Map.of("testReport", "docs/scenario-runs/agent-test.json"),
                "docs", Map.of("docsPath", "docs/architecture.md"));

        final CyclicBarrier barrier = new CyclicBarrier(2);
        final Set<String> sawConcurrently = ConcurrentHashMap.newKeySet();
        volatile boolean barrierTripped = false;

        @Override
        public AgentInvocationResult invoke(AgentInvocationTask task) {
            String nodeId = task.nodeId();
            if (nodeId.equals("test") || nodeId.equals("docs")) {
                sawConcurrently.add(nodeId);
                try {
                    barrier.await(8, TimeUnit.SECONDS);
                    barrierTripped = true;
                } catch (TimeoutException | BrokenBarrierException e) {
                    return new AgentInvocationResult(1, "", "not run concurrently: " + e, false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new AgentInvocationResult(1, "", "interrupted", false);
                }
            }
            Map<String, String> artifacts = ARTIFACTS.getOrDefault(nodeId, Map.of());
            return new AgentInvocationResult(0, envelope(artifacts), "", false);
        }

        private static String envelope(Map<String, String> artifacts) {
            StringBuilder sb = new StringBuilder("{\"status\":\"complete\",\"artifacts\":{");
            String sep = "";
            for (var e : artifacts.entrySet()) {
                sb.append(sep).append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
                sep = ",";
            }
            return sb.append("},\"notes\":\"").append(LONG_NOTES).append("\"}").toString();
        }
    }

    @TestConfiguration
    static class AgentParallelConfig {

        @Bean
        LatchingAgentPort latchingAgentPort() {
            return new LatchingAgentPort();
        }

        /** {@code AgentNodeExecutor} is normally @ConditionalOnProperty(mode=agent); wire it explicitly. */
        @Bean
        AgentNodeExecutor agentNodeExecutor(AgentInvocationPort port, ExecutorProperties properties) {
            return new AgentNodeExecutor(port, properties);
        }
    }
}
