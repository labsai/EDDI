/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every step records the agent version that ran it, and the first step after a
 * conversation moved to another compatible version records where it came from.
 */
@DisplayName("Conversation — the agent version of each step")
class ConversationAgentVersionTest {

    private ConversationMemory memory;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv-1", "agent-1", 5, "user-1");
        memory.setCompatibilityGeneration(2);
        var workflow = mock(IExecutableWorkflow.class);
        when(workflow.getLifecycleManager()).thenReturn(mock(ILifecycleManager.class));
        when(workflow.getWorkflowId()).thenReturn("wf-1");
        conversation = new Conversation(List.of(workflow), memory, mock(IPropertiesHandler.class),
                mock(IConversation.IConversationOutputRenderer.class));
    }

    private Integer versionOfCurrentStep() {
        var data = memory.getCurrentStep().getLatestData(MemoryKeys.AGENT_VERSION);
        return data != null ? data.getResult() : null;
    }

    private Map<String, Integer> changeOnCurrentStep() {
        var data = memory.getCurrentStep().getLatestData(MemoryKeys.AGENT_VERSION_CHANGE);
        return data != null ? data.getResult() : null;
    }

    @Test
    @DisplayName("the start turn records the version it ran on")
    void startTurnRecordsVersion() throws Exception {
        conversation.init(new HashMap<>());

        assertEquals(5, versionOfCurrentStep());
        assertNull(changeOnCurrentStep(), "nothing moved");
    }

    @Test
    @DisplayName("after a move, the next step records the new version and where it came from — once")
    void moveIsRecordedOnce() throws Exception {
        conversation.init(new HashMap<>());

        memory.switchAgentVersion(7);
        conversation.say("hello", new HashMap<>());

        assertEquals(7, versionOfCurrentStep());
        assertEquals(Map.of("from", 5, "to", 7), changeOnCurrentStep());

        conversation.say("again", new HashMap<>());

        assertEquals(7, versionOfCurrentStep());
        assertNull(changeOnCurrentStep(), "the move belongs to the step that first ran on the new version");
    }

    @Test
    @DisplayName("a rerun on another version records the version that re-ran the step")
    void rerunRecordsVersion() throws Exception {
        conversation.init(new HashMap<>());
        conversation.say("hello", new HashMap<>());

        memory.switchAgentVersion(6);
        conversation.rerun(new HashMap<>());

        assertEquals(6, versionOfCurrentStep());
        assertEquals(Map.of("from", 5, "to", 6), changeOnCurrentStep());
    }

    @Test
    @DisplayName("two moves before a step records the version the conversation started from")
    void twoMovesRecordTheOrigin() throws Exception {
        conversation.init(new HashMap<>());

        memory.switchAgentVersion(6);
        memory.switchAgentVersion(7);
        conversation.say("hello", new HashMap<>());

        assertEquals(Map.of("from", 5, "to", 7), changeOnCurrentStep());
    }

    /**
     * Detailed snapshots list a step's data in insertion order, and clients read it
     * by position (AgentEngineIT reads conversationStep[0]). Recording the version
     * first shifted every entry by one; it goes last.
     */
    @Test
    @DisplayName("the version is recorded after the step's own data, keeping its positions")
    void recordedLast() throws Exception {
        conversation.init(new HashMap<>());
        memory.switchAgentVersion(7);
        conversation.say("hello", new HashMap<>());

        var keys = memory.getCurrentStep().getAllElements().stream().map(data -> data.getKey()).toList();
        assertEquals(List.of(MemoryKeys.AGENT_VERSION.key(), MemoryKeys.AGENT_VERSION_CHANGE.key()), keys.subList(keys.size() - 2, keys.size()));
        assertEquals("input:initial", keys.getFirst(), "the step's own data keeps position 0");
    }

    @Test
    @DisplayName("moving to the version it is already on is no move")
    void sameVersionIsNoMove() throws Exception {
        conversation.init(new HashMap<>());

        memory.switchAgentVersion(5);
        conversation.say("hello", new HashMap<>());

        assertNull(changeOnCurrentStep());
    }
}
