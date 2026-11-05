/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The approval-card context belongs to one pause: resuming clears it with the
 * bookmark, so the next pause renders its own and a conversation that went on
 * never shows the previous one.
 */
@DisplayName("Conversation clears the approval context on resume")
class ConversationApprovalContextClearTest {

    @Test
    @DisplayName("a rejected resume clears hitlApprovalContext")
    void resumeClearsTheContext() throws Exception {
        var memory = new ConversationMemory("conv1", "agent1", 1, "user1");
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedWorkflowId("wf1");
        memory.setHitlPausedAbsoluteTaskIndex(1);
        memory.setHitlApprovalContext(RenderedApprovalContext.of(List.of(new Item("Draft", "text", false, "agent", null, null)), false));

        var workflow = mock(IExecutableWorkflow.class);
        var lifecycleManager = mock(ILifecycleManager.class);
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        var propertiesHandler = mock(IPropertiesHandler.class);
        when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
        var conversation = new Conversation(List.of(workflow), memory, propertiesHandler, mock(IConversation.IConversationOutputRenderer.class));

        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.REJECTED);
        conversation.resume(decision);

        assertNull(memory.getHitlApprovalContext());
    }
}
