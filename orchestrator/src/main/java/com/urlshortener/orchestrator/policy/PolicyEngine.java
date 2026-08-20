package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.domain.NodeExecutionEntity;
import com.urlshortener.orchestrator.domain.WorkflowRunEntity;
import org.springframework.stereotype.Component;

/**
 * Evaluates the small gate DSL referenced from {@code entryGate}/{@code exitGate} fields in
 * workflow YAML. These are the policy guardrails for security/compliance/change-control the
 * assignment asks for: a node cannot start or complete unless its gates pass.
 *
 * <p>Supported expressions:
 * <ul>
 *   <li>{@code requireContext:<key>} — run context must contain a non-blank value for key</li>
 *   <li>{@code requireArtifact:<key>} — the completing node's own artifacts must contain key</li>
 *   <li>{@code denyIfContext:<key>=<value>} — run context must NOT hold that exact value for key</li>
 * </ul>
 * A null/blank gate expression always passes.
 */
@Component
public class PolicyEngine {

    public PolicyResult checkEntry(String gateExpression, WorkflowRunEntity run) {
        return evaluate(gateExpression, run, null);
    }

    public PolicyResult checkExit(String gateExpression, WorkflowRunEntity run, NodeExecutionEntity node) {
        return evaluate(gateExpression, run, node);
    }

    private PolicyResult evaluate(String gateExpression, WorkflowRunEntity run, NodeExecutionEntity node) {
        if (gateExpression == null || gateExpression.isBlank()) {
            return PolicyResult.allow();
        }
        int colon = gateExpression.indexOf(':');
        if (colon < 0) {
            throw new IllegalStateException("Malformed policy gate expression: " + gateExpression);
        }
        String type = gateExpression.substring(0, colon);
        String arg = gateExpression.substring(colon + 1);

        return switch (type) {
            case "requireContext" -> {
                String value = run.getContext().get(arg);
                yield (value != null && !value.isBlank())
                        ? PolicyResult.allow()
                        : PolicyResult.deny("required context key '" + arg + "' is missing");
            }
            case "requireArtifact" -> {
                if (node == null) {
                    yield PolicyResult.deny("requireArtifact gate used outside of node exit evaluation");
                }
                String value = node.getArtifacts().get(arg);
                yield (value != null && !value.isBlank())
                        ? PolicyResult.allow()
                        : PolicyResult.deny("required artifact '" + arg + "' was not produced");
            }
            case "denyIfContext" -> {
                int eq = arg.indexOf('=');
                if (eq < 0) {
                    throw new IllegalStateException("Malformed denyIfContext gate expression: " + gateExpression);
                }
                String key = arg.substring(0, eq);
                String forbiddenValue = arg.substring(eq + 1);
                String actual = run.getContext().get(key);
                yield forbiddenValue.equals(actual)
                        ? PolicyResult.deny("context '" + key + "' = '" + forbiddenValue + "' is forbidden by policy")
                        : PolicyResult.allow();
            }
            default -> throw new IllegalStateException("Unknown policy gate type: " + type);
        };
    }
}
