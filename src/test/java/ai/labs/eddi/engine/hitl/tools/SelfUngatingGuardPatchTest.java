/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The server-side half of the Manager's {@code gate-guard.ts} for an RFC 6902
 * patch: the whole-document key test cannot see a patch that names the gate in
 * a path, so an approved PATCH from Slack, MCP or REST would otherwise pass.
 */
@DisplayName("SelfUngatingGuard — JSON Patch of an LLM configuration")
class SelfUngatingGuardPatchTest {

    private static final String URI = "https://eddi.example/llmstore/llms/5f1c0f3a9d8e4b2a7c6d1e0f?version=3";
    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private static String refusal(String body) {
        return SelfUngatingGuard.refusal("PATCH", URI, body, AGENT);
    }

    @Test
    @DisplayName("an ordinary edit may run")
    void ordinaryEdit() {
        assertNull(refusal("[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"m\"},"
                + "{\"op\":\"add\",\"path\":\"/tasks/0/knowledgeBases\",\"value\":[{\"name\":\"docs\"}]},"
                + "{\"op\":\"remove\",\"path\":\"/tasks/0/parameters/temperature\"},{\"op\":\"test\",\"path\":\"/tasks/0/type\",\"value\":\"x\"}]"));
    }

    @Test
    @DisplayName("a path or from naming the gate is refused, for any operation and in any case")
    void pathNamingTheGate() {
        for (String op : new String[]{"add", "replace", "remove", "test"}) {
            assertEquals(SelfUngatingGuard.GATE_WRITE_REASON,
                    refusal("[{\"op\":\"" + op + "\",\"path\":\"/tasks/0/toolApprovals/requireApproval\",\"value\":[]}]"), op);
        }
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON, refusal("[{\"op\":\"add\",\"path\":\"/tasks/0/TOOLAPPROVALS\",\"value\":{}}]"));
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON,
                refusal("[{\"op\":\"move\",\"from\":\"/tasks/0/toolApprovals\",\"path\":\"/tasks/1/x\"}]"));
    }

    @Test
    @DisplayName("a value carrying the gate is refused, wherever it is put")
    void valueCarryingTheGate() {
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON, refusal("[{\"op\":\"replace\",\"path\":\"/tasks\",\"value\":[{\"toolApprovals\":{}}]}]"));
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON,
                refusal("[{\"op\":\"replace\",\"path\":\"\",\"value\":{\"tasks\":[{\"a\":{\"toolApprovals\":{}}}]}}]"));
    }

    @Test
    @DisplayName("copy, move and unknown operations are refused")
    void unsupportedOperations() {
        assertEquals(SelfUngatingGuard.PATCH_OP_REASON, refusal("[{\"op\":\"copy\",\"from\":\"/tasks/0\",\"path\":\"/tasks/1\"}]"));
        assertEquals(SelfUngatingGuard.PATCH_OP_REASON, refusal("[{\"op\":\"move\",\"from\":\"/tasks/0\",\"path\":\"/tasks/1\"}]"));
        assertEquals(SelfUngatingGuard.PATCH_OP_REASON, refusal("[{\"op\":\"frobnicate\",\"path\":\"/tasks\"}]"));
        assertEquals(SelfUngatingGuard.PATCH_OP_REASON, refusal("[{\"path\":\"/tasks\"}]"));
    }

    @Test
    @DisplayName("naming the gate outranks an unsupported operation in the same patch")
    void gateOutranks() {
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON, refusal(
                "[{\"op\":\"copy\",\"from\":\"/a\",\"path\":\"/b\"},{\"op\":\"add\",\"path\":\"/tasks/0/toolApprovals\",\"value\":{}}]"));
    }

    @Test
    @DisplayName("an unreadable patch is refused, and a non-object entry is unverifiable")
    void unverifiable() {
        assertEquals(SelfUngatingGuard.UNVERIFIABLE_REASON, refusal("[{\"op\":\"add\""));
        assertEquals(SelfUngatingGuard.UNVERIFIABLE_REASON, refusal("[\"add /x\"]"));
    }

    @Test
    @DisplayName("a patch of another store is not inspected, and a PUT keeps the whole-document test")
    void scope() {
        assertNull(SelfUngatingGuard.refusal("PATCH", "https://eddi.example/apicallstore/apicalls/abc?version=1",
                "[{\"op\":\"add\",\"path\":\"/x/toolApprovals\",\"value\":1}]", AGENT));
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON, SelfUngatingGuard.refusal("PUT", URI, "{\"tasks\":[{\"toolApprovals\":{}}]}", AGENT));
    }
}
