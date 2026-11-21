/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.TurnOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The email approval gate's timeout (email plan v3 §8.1): a pause the gate
 * caused is {@code AUTO_REJECT} after the configured expiry, whatever the
 * agent's tool approvals or rules said; any other pause of a mail turn never
 * auto-approves; a turn without the gate is left alone.
 */
class ConversationHitlServiceEmailGateTimeoutTest {

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv1", "agent1", 1, "email:int1:alice@example.com");
        memory.setOrigin(TurnOrigin.EMAIL);
        memory.getCurrentStep().storeData(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("everyTurn", List.of(), true, null, "held")));
    }

    private void toolPause(String gateReason, HitlTimeoutPolicy agentPolicy) {
        memory.setHitlPauseType("TOOL_CALL");
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setToolName("createOrder");
        call.setGateReason(gateReason);
        var batch = new PendingToolCallBatch();
        batch.setCalls(List.of(call));
        memory.setHitlPendingToolCalls(batch);
        memory.setHitlTimeoutPolicy(agentPolicy);
        memory.setHitlApprovalTimeout("PT1M");
    }

    @Test
    @DisplayName("a pause before the agent runs is AUTO_REJECT after the expiry, with the gate's reason")
    void preRunPause() {
        memory.setHitlPauseType("RULE");
        memory.setHitlPauseReason("the agent designer's reason");
        memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
        memory.getCurrentStep().storeData(new Data<>(EmailApprovalGate.PRE_RUN_MARKER, Boolean.TRUE));

        ConversationHitlService.applyEmailGateTimeout(memory, "PT72H");

        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy());
        assertEquals("PT72H", memory.getHitlApprovalTimeout());
        assertEquals("held", memory.getHitlPauseReason());
    }

    @Test
    @DisplayName("a tool pause holding an email-gated call beats an AUTO_APPROVE rule of the agent")
    void emailGatedToolPause() {
        toolPause(EmailApprovalGate.GATE_REASON, HitlTimeoutPolicy.AUTO_APPROVE);

        ConversationHitlService.applyEmailGateTimeout(memory, "PT72H");

        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy());
        assertEquals("PT72H", memory.getHitlApprovalTimeout());
    }

    @Test
    @DisplayName("a pause the agent's gate caused on a mail turn never auto-approves, and keeps its timeout")
    void agentPauseDemoted() {
        toolPause("createOrder", HitlTimeoutPolicy.AUTO_APPROVE);

        ConversationHitlService.applyEmailGateTimeout(memory, "PT72H");

        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy());
        assertEquals("PT1M", memory.getHitlApprovalTimeout());
    }

    @Test
    @DisplayName("an untainted turn keeps the agent's timeout")
    void untainted() {
        memory = new ConversationMemory("conv2", "agent1", 1, "user1");
        memory.getCurrentStep().storeData(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("actions", List.of(), false, null, null)));
        toolPause(EmailApprovalGate.GATE_REASON, HitlTimeoutPolicy.AUTO_APPROVE);

        ConversationHitlService.applyEmailGateTimeout(memory, "PT72H");

        assertEquals(HitlTimeoutPolicy.AUTO_APPROVE, memory.getHitlTimeoutPolicy());
    }
}
