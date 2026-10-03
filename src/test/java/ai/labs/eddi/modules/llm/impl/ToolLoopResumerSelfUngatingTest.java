/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * The server-side form of the Manager's {@code self-guard.ts} and
 * {@code gate-guard.ts}: an approved call that would take the acting agent's
 * approval gate away is refused where every approval surface (Manager, Slack,
 * MCP {@code resume_conversation}, REST {@code /resume}) executes it.
 */
@DisplayName("ToolLoopResumer — self-ungating refusal")
class ToolLoopResumerSelfUngatingTest {

    private static final String AGENT_ID = "68f1c0ffee0000000000a9e7";
    private static final String OTHER_AGENT_ID = "aaaabbbbccccddddeeeeffff";

    private ToolLoopResumer resumer;

    @BeforeEach
    void setUp() {
        resumer = new ToolLoopResumer(mock(AgentOrchestrator.class), mock(ToolLoopRunner.class),
                mock(ToolApprovalGateSupport.class), mock(ChatTranscriptCodec.class), mock(IHitlToolJournalStore.class));
    }

    private static PendingToolCallBatch.PendingToolCall call(String arguments) {
        var c = new PendingToolCallBatch.PendingToolCall();
        c.setCallId("call-1");
        c.setToolName("write");
        c.setArgumentsRaw(arguments);
        return c;
    }

    private static Map<String, ToolRequestResolver> resolving(String method, String uri, String body) {
        return Map.of("write", request -> new ResolvedRequest(method, uri, Map.of(), Map.of(), body, "fp"));
    }

    @Test
    @DisplayName("refuses updateResourceUri on the acting agent — the hinge of the repoint-and-redeploy loop")
    void refusesOwnUpdateResourceUri() {
        String reason = resumer.selfUngating(call("{}"), null,
                resolving("PUT", "http://eddi/agentstore/agents/" + AGENT_ID + "/updateResourceUri?version=3", "eddi://x"), AGENT_ID);
        assertEquals(SelfUngatingGuard.OWN_AGENT_REASON, reason);
    }

    @Test
    @DisplayName("matches the acting agent id through casing and percent-encoding")
    void refusesOwnAgentEncoded() {
        assertEquals(SelfUngatingGuard.OWN_AGENT_REASON, resumer.selfUngating(call("{}"), null,
                resolving("POST", "http://eddi/administration/production/deploy%2F" + AGENT_ID.toUpperCase(), null), AGENT_ID));
    }

    @Test
    @DisplayName("allows a read of its own configuration and a write to another agent")
    void allowsReadsAndOtherAgents() {
        assertNull(resumer.selfUngating(call("{}"), null,
                resolving("GET", "http://eddi/agentstore/agents/" + AGENT_ID + "?version=1", null), AGENT_ID));
        assertNull(resumer.selfUngating(call("{}"), null,
                resolving("PUT", "http://eddi/agentstore/agents/" + OTHER_AGENT_ID + "/updateResourceUri", "eddi://x"), AGENT_ID));
    }

    @Test
    @DisplayName("refuses an LLM-config write whose body carries toolApprovals at any depth")
    void refusesGateCarryingLlmWrite() {
        String body = "{\"tasks\":[{\"actions\":[\"*\"],\"toolApprovals\":{\"requireApproval\":[]}}]}";
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON, resumer.selfUngating(call("{}"), null,
                resolving("PUT", "http://eddi/llmstore/llms/" + OTHER_AGENT_ID + "?version=1", body), AGENT_ID));
    }

    @Test
    @DisplayName("allows an LLM-config write without toolApprovals, and refuses one it cannot parse")
    void llmWriteWithoutGateAllowed_unparseableRefused() {
        assertNull(resumer.selfUngating(call("{}"), null,
                resolving("PUT", "http://eddi/llmstore/llms/abc?version=1", "{\"tasks\":[{\"systemMessage\":\"hi\"}]}"), AGENT_ID));
        assertEquals(SelfUngatingGuard.UNVERIFIABLE_REASON, resumer.selfUngating(call("{}"), null,
                resolving("PUT", "http://eddi/llmstore/llms/abc?version=1", "{not json"), AGENT_ID));
    }

    @Test
    @DisplayName("checks the amended arguments, and falls back to raw arguments without a resolver")
    void amendedAndUnresolvable() {
        Map<String, ToolRequestResolver> echo = Map.of("write",
                request -> new ResolvedRequest("PUT", "http://eddi/agentstore/agents/" + request.arguments(), Map.of(), Map.of(), null, "fp"));
        assertEquals(SelfUngatingGuard.OWN_AGENT_REASON, resumer.selfUngating(call(OTHER_AGENT_ID), AGENT_ID, echo, AGENT_ID));

        assertEquals(SelfUngatingGuard.OWN_AGENT_REASON,
                resumer.selfUngating(call("{\"agentId\":\"" + AGENT_ID + "\"}"), null, Map.of(), AGENT_ID));
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON,
                resumer.selfUngating(call("{\"path\":\"/llmstore/llms/x\",\"body\":{\"toolApprovals\":{}}}"), null, Map.of(), AGENT_ID));
    }

    @Test
    @DisplayName("a missing acting agent id refuses nothing on that rule")
    void blankAgentIdRefusesNothing() {
        assertNull(resumer.selfUngating(call("{}"), null,
                resolving("PUT", "http://eddi/agentstore/agents/" + OTHER_AGENT_ID, "{}"), null));
        assertNull(resumer.selfUngating(call("{}"), null,
                resolving("PUT", "http://eddi/agentstore/agents/" + OTHER_AGENT_ID, "{}"), " "));
    }
}
