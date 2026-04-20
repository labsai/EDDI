package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Conversation Tests")
class ConversationTest {

    private IConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IConversation.IConversationOutputRenderer outputRenderer;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;
    private IWritableConversationStep currentStep;
    private IConversationProperties conversationProperties;

    @BeforeEach
    void setUp() {
        memory = mock(IConversationMemory.class);
        propertiesHandler = mock(IPropertiesHandler.class);
        outputRenderer = mock(IConversation.IConversationOutputRenderer.class);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);
        currentStep = mock(IWritableConversationStep.class);
        conversationProperties = mock(IConversationProperties.class);

        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getConversationProperties()).thenReturn(conversationProperties);
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
    }

    private Conversation createConversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
    }

    @Nested
    @DisplayName("isEnded / endConversation")
    class StateTests {

        @Test
        @DisplayName("isEnded returns false when state is READY")
        void notEnded() {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            var conv = createConversation();
            assertFalse(conv.isEnded());
        }

        @Test
        @DisplayName("isEnded returns true when state is ENDED")
        void isEnded() {
            when(memory.getConversationState()).thenReturn(ConversationState.ENDED);
            var conv = createConversation();
            assertTrue(conv.isEnded());
        }

        @Test
        @DisplayName("endConversation sets state to ENDED")
        void endConversation() {
            var conv = createConversation();
            conv.endConversation();
            verify(memory).setConversationState(ConversationState.ENDED);
        }
    }

    @Nested
    @DisplayName("getConversationMemory")
    class MemoryTests {

        @Test
        @DisplayName("returns the injected memory")
        void returnsMemory() {
            var conv = createConversation();
            assertSame(memory, conv.getConversationMemory());
        }
    }

    @Nested
    @DisplayName("init")
    class InitTests {

        @Test
        @DisplayName("init sets state to READY and adds CONVERSATION_START action")
        void initSetsReady() throws Exception {
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
            when(propertiesHandler.getUserMemoryConfig()).thenReturn(null);
            when(workflow.getWorkflowId()).thenReturn("wf1");

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(memory).setConversationState(ConversationState.READY);
            verify(currentStep).set(any(), argThat(list -> list instanceof List<?> l && l.contains("CONVERSATION_START")));
        }

        @Test
        @DisplayName("init loads user properties from store")
        void initLoadsUserProperties() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("user1");
            when(memory.getAgentId()).thenReturn("agent1");

            var entry = new UserMemoryEntry(null, "user1", "language", "en",
                    "fact", Property.Visibility.self, "agent1", List.of(), "conv1",
                    false, 0, java.time.Instant.now(), java.time.Instant.now());
            when(store.getVisibleEntries(eq("user1"), eq("agent1"), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of(entry));

            when(workflow.getWorkflowId()).thenReturn("wf1");

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(conversationProperties).put(eq("language"), any(Property.class));
        }

        @Test
        @DisplayName("init with null store — skips property loading")
        void initNullStore() throws Exception {
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
            when(workflow.getWorkflowId()).thenReturn("wf1");

            var conv = createConversation();
            assertDoesNotThrow(() -> conv.init(new HashMap<>()));
        }
    }

    @Nested
    @DisplayName("say")
    class SayTests {

        @Test
        @DisplayName("say throws ConversationNotReadyException when IN_PROGRESS")
        void sayWhenInProgress() {
            when(memory.getConversationState()).thenReturn(ConversationState.IN_PROGRESS);
            var conv = createConversation();
            assertThrows(IConversation.ConversationNotReadyException.class,
                    () -> conv.say("hello", Map.of()));
        }
    }

    @Nested
    @DisplayName("rerun")
    class RerunTests {

        @Test
        @DisplayName("rerun throws when IN_PROGRESS")
        void rerunWhenInProgress() {
            when(memory.getConversationState()).thenReturn(ConversationState.IN_PROGRESS);
            var conv = createConversation();
            assertThrows(IConversation.ConversationNotReadyException.class,
                    () -> conv.rerun(Map.of()));
        }
    }
}
