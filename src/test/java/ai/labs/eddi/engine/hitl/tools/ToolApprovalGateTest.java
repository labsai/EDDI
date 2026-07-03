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
}
