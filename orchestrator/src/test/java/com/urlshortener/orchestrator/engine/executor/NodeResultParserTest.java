package com.urlshortener.orchestrator.engine.executor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reply-parsing contract shared by the {@code llm} and {@code agent} executors. Moved here from
 * {@code LlmNodeExecutorTest} so both executors depend on one tested parser.
 */
class NodeResultParserTest {

    @Test
    void extractsStatusAndArtifacts() {
        NodeExecutionResult r = NodeResultParser.parse(
                "{\"status\":\"complete\",\"artifacts\":{\"commit\":\"deadbee\"},\"notes\":\"done\"}");
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
        assertThat(r.artifacts()).containsEntry("commit", "deadbee");
        assertThat(r.notes()).isEqualTo("done");
    }

    @Test
    void explicitFailStatusMapsToFailWithNotes() {
        NodeExecutionResult r = NodeResultParser.parse(
                "{\"status\":\"fail\",\"artifacts\":{},\"notes\":\"cannot proceed\"}");
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(r.notes()).isEqualTo("cannot proceed");
    }

    @Test
    void unknownStatusMapsToFail() {
        NodeExecutionResult r = NodeResultParser.parse("{\"status\":\"partly\",\"notes\":\"\"}");
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(r.notes()).contains("unknown status");
    }

    @Test
    void proseAndFencesAroundTheObjectAreTolerated() {
        NodeExecutionResult r = NodeResultParser.parse(
                "Sure, here is the result:\n```json\n{\"status\":\"complete\",\"artifacts\":{},\"notes\":\"ok\"}\n```\n");
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
    }

    @Test
    void strayBracesInThePreambleDoNotBreakExtraction() {
        // Regression: the live 004 documentation node failed here — its final message described the
        // `GET /api/urls/{code}/qr` path (and a `${...}` snippet) before the real result object, so
        // a first-'{' / last-'}' span fed Jackson "{code}/qr … {" and threw.
        NodeExecutionResult r = NodeResultParser.parse(
                "Updated docs for GET /api/urls/{code}/qr and the ${baseUrl} note.\n"
                        + "{\"status\":\"complete\",\"artifacts\":{\"docsPath\":\"docs/architecture.md\"},"
                        + "\"notes\":\"documented the {code} path param and 200/404/410\"}");
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
        assertThat(r.artifacts()).containsEntry("docsPath", "docs/architecture.md");
        assertThat(r.notes()).contains("{code}");
    }

    @Test
    void noJsonObjectMapsToFailNotException() {
        NodeExecutionResult r = NodeResultParser.parse("I cannot help with that.");
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(r.notes()).contains("unparseable");
    }

    @Test
    void nullAndEmptyMapToFail() {
        assertThat(NodeResultParser.parse(null).outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(NodeResultParser.parse("").outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
    }

    @Test
    void extractJsonObjectIsolatesTheObjectOrReturnsNull() {
        assertThat(NodeResultParser.extractJsonObject("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(NodeResultParser.extractJsonObject("no json here")).isNull();
    }
}
