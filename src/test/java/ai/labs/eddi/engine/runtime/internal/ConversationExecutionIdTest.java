/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.health.TurnExecutions;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Agent Health execution id, wired into the turn paths of
 * {@link Conversation}: init and say stamp their step, a rerun and a HITL
 * resume keep the step's id, and a say after an undo starts a new one that
 * lists the undone execution.
 */
@DisplayName("Conversation — Agent Health execution ids")
class ConversationExecutionIdTest {

    private ConversationMemory memory;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv-1", "agent-1", 5, "user-1");
        var workflow = mock(IExecutableWorkflow.class);
        when(workflow.getLifecycleManager()).thenReturn(mock(ILifecycleManager.class));
        when(workflow.getWorkflowId()).thenReturn("wf-1");
        conversation = new Conversation(List.of(workflow), memory, mock(IPropertiesHandler.class),
                mock(IConversation.IConversationOutputRenderer.class));
    }

    private String currentId() {
        return TurnExecutions.executionId(memory.getCurrentStep());
    }

    @Test
    @DisplayName("init and say stamp their step, before agent:version so the version keys stay last")
    void initAndSayStamp() throws Exception {
        conversation.init(new HashMap<>());
        String start = currentId();
        assertNotNull(start);

        conversation.say("hello", new HashMap<>());
        String say = currentId();
        assertNotNull(say);
        assertNotEquals(start, say);

        var keys = memory.getCurrentStep().getAllElements().stream().map(data -> data.getKey()).toList();
        assertEquals(MemoryKeys.AGENT_VERSION.key(), keys.getLast());
        assertTrue(keys.indexOf(MemoryKeys.HEALTH_EXECUTION_ID) < keys.indexOf(MemoryKeys.AGENT_VERSION.key()));
    }

    @Test
    @DisplayName("a rerun keeps the step's id")
    void rerunKeepsId() throws Exception {
        conversation.init(new HashMap<>());
        conversation.say("hello", new HashMap<>());
        String id = currentId();

        conversation.rerun(new HashMap<>());

        assertEquals(id, currentId());
    }

    @Test
    @DisplayName("a HITL resume continues the paused step under the same id")
    void resumeKeepsId() throws Exception {
        conversation.init(new HashMap<>());
        conversation.say("hello", new HashMap<>());
        String paused = currentId();
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedWorkflowId("wf-1");
        memory.setHitlPausedAbsoluteTaskIndex(0);
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);

        conversation.resume(decision);

        assertEquals(paused, currentId());
    }

    @Test
    @DisplayName("say after undo: a new id, and the undone one listed for discarding")
    void sayAfterUndo() throws Exception {
        conversation.init(new HashMap<>());
        conversation.say("hello", new HashMap<>());
        String undone = currentId();
        memory.undoLastStep();

        conversation.say("hello again", new HashMap<>());

        assertNotEquals(undone, currentId());
        assertEquals(List.of(undone), TurnExecutions.discardedBy(memory.getCurrentStep()));
    }
}
