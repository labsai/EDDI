/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolApprovalGateTest {

    private static ToolExecutionRequest req(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static ToolApprovalsConfig cfg(List<String> require, List<String> exempt) {
        var c = new ToolApprovalsConfig();
        c.setRequireApproval(require);
        c.setExempt(exempt);
        return c;
    }

    // ==================== endpoint-qualified patterns ====================

    /** Two generated tools whose names say nothing about what they do. */
    private static final Map<String, String> SOURCES = Map.of("listAgents", "http", "createAgent", "http", "updateLlm", "http");
    private static final Map<String, String> ENDPOINTS = Map.of(
            "listAgents", "get:/agentstore/agents/descriptors",
            "createAgent", "post:/agentstore/agents",
            "updateLlm", "put:/llmstore/llms/{id}");

    private static List<ToolExecutionRequest> allThree() {
        return List.of(req("1", "listAgents"), req("2", "createAgent"), req("3", "updateLlm"));
    }

    @Test
    void methodQualifiedPattern_gatesEveryMutationWithoutNamingAnyTool() {
        // The point of the form: nobody maintains a list of write tool names, so a
        // newly generated endpoint cannot arrive ungated.
        var gate = new ToolApprovalGate();
        var result = gate.classify(allThree(), SOURCES, ENDPOINTS,
                cfg(List.of("http.post:*", "http.put:*", "http.patch:*", "http.delete:*"), null), Set.of());

        assertEquals(List.of("createAgent", "updateLlm"), result.gated().stream().map(ToolExecutionRequest::name).toList());
        assertEquals(List.of("listAgents"), result.allowed().stream().map(ToolExecutionRequest::name).toList());
    }

    @Test
    void endpointQualifiedPattern_addressesOneEndpoint() {
        // Different POSTs carry different weight, so a pattern must be able to name
        // the endpoint rather than only the method.
        var gate = new ToolApprovalGate();
        var result = gate.classify(allThree(), SOURCES, ENDPOINTS, cfg(List.of("http.post:/agentstore/agents"), null), Set.of());

        assertEquals(List.of("createAgent"), result.gated().stream().map(ToolExecutionRequest::name).toList());
    }

    @Test
    void pathTemplateBracesAreLiterals_notRegexQuantifiers() {
        var gate = new ToolApprovalGate();
        var result = gate.classify(allThree(), SOURCES, ENDPOINTS, cfg(List.of("http.put:/llmstore/llms/{id}"), null), Set.of());

        assertEquals(List.of("updateLlm"), result.gated().stream().map(ToolExecutionRequest::name).toList());
    }

    @Test
    void exemptMayAlsoBeEndpointQualified() {
        // Gate everything, then exempt reads — the only safe direction, since a
        // missed exemption costs an approval prompt rather than an ungated write.
        var gate = new ToolApprovalGate();
        var result = gate.classify(allThree(), SOURCES, ENDPOINTS, cfg(List.of("http:*"), List.of("http.get:*")), Set.of());

        assertEquals(List.of("createAgent", "updateLlm"), result.gated().stream().map(ToolExecutionRequest::name).toList());
    }

    @Test
    void methodQualifierIsRejectedForSourcesThatCarryNoEndpoint() {
        // mcp and a2a tools register a source and no endpoint, so "mcp.post:*"
        // would save cleanly and then match nothing — and an unmatched call is
        // allowed. Saving must fail rather than produce a gate that does nothing.
        for (String pattern : List.of("mcp.post:*", "a2a.delete:*", "memory.put:x", "builtin.get:*")) {
            assertTrue(ToolApprovalPatterns.validate(pattern).isPresent(), pattern + " must be rejected: nothing can ever match it");
        }
        assertTrue(ToolApprovalPatterns.validate("http.post:*").isEmpty(), "http is the one source that carries an endpoint");
    }

    @Test
    void aMethodQualifiedMcpPatternCannotSilentlyGateNothing() {
        // The runtime half of the guarantee above: even if such a pattern reached
        // the gate, it must not look like protection.
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "mcp_write_thing"));
        var sources = Map.of("mcp_write_thing", "mcp");

        var result = gate.classify(batch, sources, Map.of(), cfg(List.of("mcp.post:*"), null), Set.of());
        assertTrue(result.gated().isEmpty(), "documents the fail-open: this is why save-time validation must reject it");
    }

    @Test
    void existingSourcePatternsKeepWorkingWithoutEndpointData() {
        // Backward compatibility: agents configured before endpoint provenance
        // existed pass an empty map and must gate exactly as they did.
        var gate = new ToolApprovalGate();
        var result = gate.classify(allThree(), SOURCES, Map.of(), cfg(List.of("http:*"), null), Set.of());

        assertEquals(3, result.gated().size(), "http:* must still gate every http tool");
    }

    @Test
    void nullOrEmptyConfig_gatesNothing() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "delete_account"));
        assertTrue(gate.classify(batch, Map.of(), null, Set.of()).gated().isEmpty());
        assertTrue(gate.classify(batch, Map.of(), cfg(null, null), Set.of()).gated().isEmpty());
        assertTrue(gate.classify(batch, Map.of(), cfg(List.of(), List.of("x")), Set.of()).gated().isEmpty());
    }

    @Test
    void sourceQualifiedAndBareName_bothMatch() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "read_file"), req("2", "getCurrentDateTime"));
        var sources = Map.of("read_file", "mcp", "getCurrentDateTime", "builtin");
        var result = gate.classify(batch, sources, cfg(List.of("mcp:*"), null), Set.of());
        assertEquals(1, result.gated().size());
        assertEquals("read_file", result.gated().get(0).name());
        assertEquals("mcp:*", result.gateReasonByCallId().get("1"));
    }

    @Test
    void exemptBeatsRequire() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "read_file"), req("2", "write_file"));
        var sources = Map.of("read_file", "mcp", "write_file", "mcp");
        var result = gate.classify(batch, sources, cfg(List.of("mcp:*"), List.of("mcp:read_*")), Set.of());
        assertEquals(List.of("write_file"), result.gated().stream().map(ToolExecutionRequest::name).toList());
    }

    @Test
    void clearedCallIds_neverReGated() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "delete_account"));
        var result = gate.classify(batch, Map.of("delete_account", "http"), cfg(List.of("delete_*"), null), Set.of("1"));
        assertTrue(result.gated().isEmpty());
    }

    @Test
    void unknownSourceForTool_stillMatchesBareName_failSafe() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "delete_account"));
        // tool missing from the sources map entirely — bare-name match must still gate
        var result = gate.classify(batch, Map.of(), cfg(List.of("delete_*"), null), Set.of());
        assertEquals(1, result.gated().size());
    }

    @Test
    void nullToolName_doesNotNpe_flowsToAllowed() {
        var gate = new ToolApprovalGate();
        // Some providers emit a malformed tool call with a null name. The gate must
        // NOT NPE while pattern-matching; the null-name call matches nothing and flows
        // to `allowed` so the downstream dispatch degrades gracefully ("tool not
        // found"), as it did pre-HITL — instead of failing the whole turn.
        var batch = List.of(ToolExecutionRequest.builder().id("1").name(null).arguments("{}").build());
        var result = assertDoesNotThrow(
                () -> gate.classify(batch, Map.of(), cfg(List.of("*"), null), Set.of()));
        assertTrue(result.gated().isEmpty(), "null-name call must not be gated");
        assertEquals(1, result.allowed().size(), "null-name call must flow to allowed");
    }
}
