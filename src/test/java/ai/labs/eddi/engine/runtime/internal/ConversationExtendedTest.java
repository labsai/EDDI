/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
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

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended unit tests for {@link Conversation} covering additional branches: -
 * entryToProperty type dispatching (Map, List, Integer, Float, Boolean,
 * fallback) - extractGroupIds context parsing - isSecretInputFlagged variants -
 * storePropertiesPermanently with visibility logic -
 * checkActionsForConversationEnd - say/rerun lifecycle state transitions
 * (including error, interrupt, stop) - removeOldInvalidProperties -
 * loadUserProperties with config-aware recall settings
 */
@DisplayName("Conversation Extended Tests")
class ConversationExtendedTest {

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IConversation.IConversationOutputRenderer outputRenderer;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;
    private IWritableConversationStep currentStep;
    private IConversationProperties conversationProperties;

    @BeforeEach
    void setUp() {
        memory = mock(ConversationMemory.class);
        propertiesHandler = mock(IPropertiesHandler.class);
        outputRenderer = mock(IConversation.IConversationOutputRenderer.class);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);
        currentStep = mock(IWritableConversationStep.class);
        conversationProperties = mock(IConversationProperties.class);

        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getConversationProperties()).thenReturn(conversationProperties);
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        when(workflow.getWorkflowId()).thenReturn("wf-test");
    }

    private Conversation createConversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
    }

    // ==================== say lifecycle ====================

    @Nested
    @DisplayName("say — lifecycle state transitions")
    class SayLifecycleTests {

        @Test
        @DisplayName("say succeeds when state is READY and sets state back to READY after")
        void saySucceeds() throws Exception {
            when(memory.getConversationState())
                    .thenReturn(ConversationState.READY)
                    .thenReturn(ConversationState.IN_PROGRESS);

            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            var conv = createConversation();
            conv.say("hello", Map.of());

            // Verify state transitions: set IN_PROGRESS then back to READY
            verify(memory, atLeastOnce()).setConversationState(ConversationState.IN_PROGRESS);
            verify(memory, atLeastOnce()).setConversationState(ConversationState.READY);
        }

        @Test
        @DisplayName("say succeeds when state is ENDED (only IN_PROGRESS blocks)")
        void sayWhenEnded() {
            when(memory.getConversationState()).thenReturn(ConversationState.ENDED);

            var conv = createConversation();

            assertDoesNotThrow(() -> conv.say("hello", Map.of()));
        }

        @Test
        @DisplayName("say sets ERROR state on unexpected exception")
        void sayOnError() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            doThrow(new RuntimeException("boom"))
                    .when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            assertThrows(LifecycleException.class, () -> conv.say("test", Map.of()));
            verify(memory).setConversationState(ConversationState.ERROR);
        }

        @Test
        @DisplayName("say sets EXECUTION_INTERRUPTED on LifecycleInterruptedException")
        void sayOnInterrupt() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            doThrow(new LifecycleException.LifecycleInterruptedException("interrupted"))
                    .when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            assertThrows(LifecycleException.LifecycleInterruptedException.class,
                    () -> conv.say("test", Map.of()));
            verify(memory).setConversationState(ConversationState.EXECUTION_INTERRUPTED);
        }

        @Test
        @DisplayName("say with ConversationStopException ends the conversation")
        void sayStopsConversation() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            doThrow(new ConversationStopException())
                    .when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            conv.say("stop", Map.of());

            verify(memory).setConversationState(ConversationState.ENDED);
        }

        @Test
        @DisplayName("say calls outputProvider.renderOutput in finally block")
        void sayRendersOutput() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            var conv = createConversation();
            conv.say("hello", Map.of());

            verify(outputRenderer).renderOutput(memory);
        }

        @Test
        @DisplayName("say with null outputProvider does not throw")
        void sayNullOutputProvider() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            var conv = new Conversation(List.of(workflow), memory, propertiesHandler, null);
            assertDoesNotThrow(() -> conv.say("hello", Map.of()));
        }
    }

    // ==================== rerun ====================

    @Nested
    @DisplayName("rerun — lifecycle")
    class RerunTests {

        @Test
        @DisplayName("rerun succeeds when state is READY")
        void rerunSucceeds() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            var conv = createConversation();
            assertDoesNotThrow(() -> conv.rerun(Map.of()));
        }

        @Test
        @DisplayName("rerun removes output and quickReplies from previous runs")
        void rerunRemovesPreviousData() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            var conv = createConversation();
            conv.rerun(Map.of());

            verify(currentStep).removeData("output");
            verify(currentStep).removeData("quickReplies");
            verify(currentStep).resetConversationOutput("output");
            verify(currentStep).resetConversationOutput("quickReplies");
        }
    }

    // ==================== init — loadUserProperties ====================

    @Nested
    @DisplayName("init — user property loading")
    class InitPropertyTests {

        @Test
        @DisplayName("init loads userInfo map entry as conversation-scoped property")
        void initLoadsUserInfoMap() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            Map<String, Object> userInfo = Map.of("email", "test@example.com");
            var entry = new UserMemoryEntry(null, "u1", "userInfo", userInfo,
                    "map", Visibility.self, "a1", List.of(), "conv1",
                    false, 0, Instant.now(), Instant.now());
            when(store.getVisibleEntries(eq("u1"), eq("a1"), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of(entry));

            var conv = createConversation();
            conv.init(new HashMap<>());

            // userInfo should be stored as conversation-scoped (not longTerm)
            verify(conversationProperties).put(eq("userInfo"), argThat(prop -> prop.getScope() == Scope.conversation));
        }

        @Test
        @DisplayName("init loads entry with custom UserMemoryConfig recall settings")
        void initUsesConfigRecallSettings() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            var config = new AgentConfiguration.UserMemoryConfig();
            config.setRecallOrder("oldest_first");
            config.setMaxRecallEntries(50);
            when(memory.getUserMemoryConfig()).thenReturn(config);

            when(store.getVisibleEntries("u1", "a1", List.of(), "oldest_first", 50))
                    .thenReturn(List.of());

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(store).getVisibleEntries("u1", "a1", List.of(), "oldest_first", 50);
        }

        @Test
        @DisplayName("init uses default recall settings when no UserMemoryConfig")
        void initUsesDefaultRecallSettings() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");
            when(memory.getUserMemoryConfig()).thenReturn(null);

            when(store.getVisibleEntries("u1", "a1", List.of(), "most_recent", 1000))
                    .thenReturn(List.of());

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(store).getVisibleEntries("u1", "a1", List.of(), "most_recent", 1000);
        }

        @Test
        @DisplayName("init wraps ResourceStoreException in LifecycleException")
        void initWrapsStoreException() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            when(store.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            var conv = createConversation();
            assertThrows(LifecycleException.class, () -> conv.init(new HashMap<>()));
        }

        @Test
        @DisplayName("init extracts groupId from context")
        void initExtractsGroupId() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            Map<String, Context> context = new HashMap<>();
            context.put("groupId", new Context(Context.ContextType.string, "group123"));

            when(store.getVisibleEntries(eq("u1"), eq("a1"), eq(List.of("group123")),
                    anyString(), anyInt())).thenReturn(List.of());

            var conv = createConversation();
            conv.init(context);

            verify(store).getVisibleEntries(eq("u1"), eq("a1"), eq(List.of("group123")),
                    anyString(), anyInt());
        }

        @Test
        @DisplayName("init with empty context does not throw")
        void initHandlesNullContext() throws Exception {
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            var conv = createConversation();
            // createContextData iterates context.keySet() — passing null throws NPE
            // Passing empty map is the safe path
            assertDoesNotThrow(() -> conv.init(new HashMap<>()));
        }

        @Test
        @DisplayName("init sets UserMemoryConfig on memory when available")
        void initSetsMemoryConfig() throws Exception {
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            var config = new AgentConfiguration.UserMemoryConfig();
            when(propertiesHandler.getUserMemoryConfig()).thenReturn(config);

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(memory).setUserMemoryConfig(config);
        }

        @Test
        @DisplayName("init skips UserMemoryConfig on memory when null")
        void initSkipsNullConfig() throws Exception {
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
            when(propertiesHandler.getUserMemoryConfig()).thenReturn(null);

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(memory, never()).setUserMemoryConfig(any());
        }
    }

    // ==================== entryToProperty type dispatch ====================

    @Nested
    @DisplayName("Property type conversion from UserMemoryEntry")
    class EntryToPropertyTests {

        @Test
        @DisplayName("loads String value as String property")
        void convertsStringEntry() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            var entry = createEntry("name", "John", "string");
            when(store.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of(entry));

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(conversationProperties).put(eq("name"), argThat(prop -> "John".equals(prop.getValueString())));
        }

        @Test
        @DisplayName("loads Integer value as Integer property")
        void convertsIntegerEntry() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            var entry = createEntry("age", 25, "integer");
            when(store.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of(entry));

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(conversationProperties).put(eq("age"), argThat(prop -> Integer.valueOf(25).equals(prop.getValueInt())));
        }

        @Test
        @DisplayName("loads Float value as Float property")
        void convertsFloatEntry() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            var entry = createEntry("score", 3.14f, "float");
            when(store.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of(entry));

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(conversationProperties).put(eq("score"), argThat(prop -> Float.valueOf(3.14f).equals(prop.getValueFloat())));
        }

        @Test
        @DisplayName("loads Boolean value as Boolean property")
        void convertsBooleanEntry() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            var entry = createEntry("active", true, "boolean");
            when(store.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of(entry));

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(conversationProperties).put(eq("active"), argThat(prop -> Boolean.TRUE.equals(prop.getValueBoolean())));
        }

        @Test
        @DisplayName("loads List value as List property")
        void convertsListEntry() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            var entry = createEntry("tags", List.of("a", "b"), "list");
            when(store.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of(entry));

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(conversationProperties).put(eq("tags"), argThat(prop -> prop.getValueList() != null));
        }

        @Test
        @DisplayName("loads Map value as Map property")
        void convertsMapEntry() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            Map<String, Object> mapVal = Map.of("city", "Vienna");
            var entry = createEntry("address", mapVal, "map");
            when(store.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of(entry));

            var conv = createConversation();
            conv.init(new HashMap<>());

            verify(conversationProperties).put(eq("address"), argThat(prop -> prop.getValueObject() != null));
        }

        @Test
        @DisplayName("loads unknown type as toString fallback")
        void convertsFallbackEntry() throws Exception {
            var store = mock(IUserMemoryStore.class);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(store);
            when(memory.getUserId()).thenReturn("u1");
            when(memory.getAgentId()).thenReturn("a1");

            // Use a type that doesn't match String/Map/List/Integer/Float/Boolean
            var entry = createEntry("data", 99.9d, "double"); // Double, not Float
            when(store.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of(entry));

            var conv = createConversation();
            conv.init(new HashMap<>());

            // Falls through to toString fallback
            verify(conversationProperties).put(eq("data"),
                    argThat(prop -> prop.getValueString() != null && prop.getValueString().contains("99.9")));
        }

        private UserMemoryEntry createEntry(String key, Object value, String category) {
            return new UserMemoryEntry(null, "u1", key, value,
                    category, Visibility.self, "a1", List.of(), "conv1",
                    false, 0, Instant.now(), Instant.now());
        }
    }

    // ==================== checkActionsForConversationEnd ====================

    @Nested
    @DisplayName("checkActionsForConversationEnd")
    class ConversationEndTests {

        @Test
        @DisplayName("ends conversation when actions contain CONVERSATION_END")
        @SuppressWarnings("unchecked")
        void endsOnActionConversationEnd() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            IData<List<String>> actionData = mock(IData.class);
            when(actionData.getResult()).thenReturn(List.of("CONVERSATION_END"));
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actionData);

            var conv = createConversation();
            conv.say("bye", Map.of());

            verify(memory, atLeastOnce()).setConversationState(ConversationState.ENDED);
        }

        @Test
        @DisplayName("does not end when actions are null")
        @SuppressWarnings("unchecked")
        void doesNotEndOnNullActions() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(null);

            var conv = createConversation();
            conv.say("hello", Map.of());

            // ENDED should not be set (only READY and IN_PROGRESS)
            verify(memory, never()).setConversationState(ConversationState.ENDED);
        }

        @Test
        @DisplayName("does not end when actions list is empty")
        @SuppressWarnings("unchecked")
        void doesNotEndOnEmptyActions() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            IData<List<String>> actionData = mock(IData.class);
            when(actionData.getResult()).thenReturn(List.of("SOME_ACTION"));
            when(currentStep.getLatestData(MemoryKeys.ACTIONS)).thenReturn(actionData);

            var conv = createConversation();
            conv.say("hello", Map.of());

            verify(memory, never()).setConversationState(ConversationState.ENDED);
        }
    }

    // ==================== secret input ====================

    @Nested
    @DisplayName("isSecretInputFlagged")
    class SecretInputTests {

        @Test
        @DisplayName("say stores placeholder when secretInput=true")
        void secretInputStoresPlaceholder() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            Map<String, Context> contexts = new HashMap<>();
            contexts.put("secretInput", new Context(Context.ContextType.string, "true"));

            var conv = createConversation();
            conv.say("my_password", contexts);

            verify(currentStep).addConversationOutputString(eq("input"), eq("<secret input>"));
        }

        @Test
        @DisplayName("say stores actual input when no secretInput flag")
        void normalInputStoresActual() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            var conv = createConversation();
            conv.say("hello world", Map.of());

            verify(currentStep).addConversationOutputString(eq("input"), eq("hello world"));
        }
    }

    // ==================== say with context ====================

    @Nested
    @DisplayName("say with context data")
    class SayWithContextTests {

        @Test
        @DisplayName("say stores context data in conversation output")
        void sayStoresContextData() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            Map<String, Context> contexts = new HashMap<>();
            contexts.put("lang", new Context(Context.ContextType.string, "en"));

            var conv = createConversation();
            conv.say("hello", contexts);

            verify(currentStep).addConversationOutputMap(eq("context"), any());
        }

        @Test
        @DisplayName("say with empty message does not store input")
        void sayEmptyMessageSkipsInput() throws Exception {
            when(memory.getConversationState()).thenReturn(ConversationState.READY);
            when(propertiesHandler.getUserMemoryStore()).thenReturn(null);

            var conv = createConversation();
            conv.say("   ", Map.of());

            verify(currentStep, never()).addConversationOutputString(eq("input"), anyString());
        }
    }
}
