/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RulesEvaluationTaskTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private IRuleDeserialization behaviorSerialization;
    @Mock
    private IExpressionProvider expressionProvider;

    private RulesEvaluationTask task;

    @BeforeEach
    void setUp() {
        openMocks(this);
        task = new RulesEvaluationTask(resourceClientLibrary, jsonSerialization, behaviorSerialization, expressionProvider);
    }

    // ==================== Identity Tests ====================

    @Nested
    @DisplayName("Task Identity")
    class IdentityTests {

        @Test
        @DisplayName("getId should return correct identifier")
        void testGetId() {
            assertEquals("ai.labs.behavior", task.getId());
        }

        @Test
        @DisplayName("getType should return 'behavior_rules'")
        void testGetType() {
            assertEquals("behavior_rules", task.getType());
        }
    }

    // ==================== Execute Tests ====================

    @Nested
    @DisplayName("execute")
    class ExecuteTests {

        @Test
        @DisplayName("should evaluate rules and store success results in memory")
        void execute_successRules_storesResults() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            Rule successRule = new Rule("Greeting");
            successRule.setActions(List.of("greet"));

            var results = new RuleSetResult();
            results.getSuccessRules().add(successRule);

            var evaluator = mock(RulesEvaluator.class);
            when(evaluator.evaluate(memory)).thenReturn(results);
            when(evaluator.isAppendActions()).thenReturn(true);
            when(evaluator.isExpressionsAsActions()).thenReturn(false);

            task.execute(memory, evaluator);

            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("should store dropped success rules in memory")
        void execute_droppedSuccessRules_storesResults() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            Rule droppedRule = new Rule("DroppedRule");
            droppedRule.setActions(List.of("dropped_action"));

            var results = new RuleSetResult();
            results.getDroppedSuccessRules().add(droppedRule);

            var evaluator = mock(RulesEvaluator.class);
            when(evaluator.evaluate(memory)).thenReturn(results);
            when(evaluator.isAppendActions()).thenReturn(true);
            when(evaluator.isExpressionsAsActions()).thenReturn(false);

            task.execute(memory, evaluator);

            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("should not store actions when no rules succeed")
        void execute_noSuccessRules_noActionsStored() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var results = new RuleSetResult();

            var evaluator = mock(RulesEvaluator.class);
            when(evaluator.evaluate(memory)).thenReturn(results);
            when(evaluator.isAppendActions()).thenReturn(true);
            when(evaluator.isExpressionsAsActions()).thenReturn(false);

            task.execute(memory, evaluator);

            verify(currentStep, never()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("should wrap evaluation exceptions in LifecycleException")
        void execute_evaluationError_throwsLifecycleException() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);

            var evaluator = mock(RulesEvaluator.class);
            doThrow(new RulesEvaluator.RuleExecutionException("test error", new RuntimeException("cause"))).when(evaluator).evaluate(memory);

            assertThrows(LifecycleException.class, () -> task.execute(memory, evaluator));
        }

        @Test
        @DisplayName("should collect actions from multiple success rules")
        void execute_multipleSuccessRules_collectsAllActions() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            Rule rule1 = new Rule("Rule1");
            rule1.setActions(List.of("action_a", "action_b"));
            Rule rule2 = new Rule("Rule2");
            rule2.setActions(List.of("action_c"));

            var results = new RuleSetResult();
            results.getSuccessRules().add(rule1);
            results.getSuccessRules().add(rule2);

            var evaluator = mock(RulesEvaluator.class);
            when(evaluator.evaluate(memory)).thenReturn(results);
            when(evaluator.isAppendActions()).thenReturn(true);
            when(evaluator.isExpressionsAsActions()).thenReturn(false);

            task.execute(memory, evaluator);

            verify(currentStep, atLeastOnce()).addConversationOutputList(eq(ACTIONS.key()), anyList());
        }
    }

    // ==================== Configure Tests ====================

    @Nested
    @DisplayName("configure")
    class ConfigureTests {

        @Test
        @DisplayName("should load and deserialize behavior configuration")
        void configure_validUri_loadsBehavior() throws Exception {
            Map<String, Object> config = new HashMap<>();
            config.put("uri", "http://example.com/behavior");

            var behaviorConfig = new RuleSetConfiguration();
            when(resourceClientLibrary.getResource(any(URI.class), eq(RuleSetConfiguration.class))).thenReturn(behaviorConfig);
            when(jsonSerialization.serialize(behaviorConfig)).thenReturn("{}");

            var behaviorSet = new RuleSet();
            when(behaviorSerialization.deserialize("{}")).thenReturn(behaviorSet);

            Object result = task.configure(config, Collections.emptyMap());
            assertNotNull(result);
            assertInstanceOf(RulesEvaluator.class, result);
        }

        @Test
        @DisplayName("should throw WorkflowConfigurationException when resource loading fails")
        void configure_serviceError_throwsException() throws Exception {
            Map<String, Object> config = new HashMap<>();
            config.put("uri", "http://example.com/behavior");

            when(resourceClientLibrary.getResource(any(URI.class), eq(RuleSetConfiguration.class))).thenThrow(new ServiceException("not found"));

            assertThrows(WorkflowConfigurationException.class, () -> task.configure(config, Collections.emptyMap()));
        }

        @Test
        @DisplayName("should respect appendActions configuration override")
        void configure_appendActionsOverride() throws Exception {
            Map<String, Object> config = new HashMap<>();
            config.put("uri", "http://example.com/behavior");
            config.put("appendActions", "false");

            var behaviorConfig = new RuleSetConfiguration();
            when(resourceClientLibrary.getResource(any(URI.class), eq(RuleSetConfiguration.class))).thenReturn(behaviorConfig);
            when(jsonSerialization.serialize(behaviorConfig)).thenReturn("{}");

            var behaviorSet = new RuleSet();
            when(behaviorSerialization.deserialize("{}")).thenReturn(behaviorSet);

            Object result = task.configure(config, Collections.emptyMap());
            assertNotNull(result);
            var evaluator = (RulesEvaluator) result;
            assertFalse(evaluator.isAppendActions());
        }
    }

    // ==================== ExtensionDescriptor Tests ====================

    @Nested
    @DisplayName("getExtensionDescriptor")
    class ExtensionDescriptorTests {

        @Test
        @DisplayName("should return correct descriptor with expected fields")
        void testExtensionDescriptor() {
            ExtensionDescriptor descriptor = task.getExtensionDescriptor();

            assertNotNull(descriptor);
            assertEquals("ai.labs.behavior", descriptor.getType());
            assertEquals("Behavior Rules", descriptor.getDisplayName());

            assertTrue(descriptor.getConfigs().containsKey("uri"));
            assertTrue(descriptor.getConfigs().containsKey("appendActions"));
            assertTrue(descriptor.getConfigs().containsKey("expressionsAsActions"));

            assertEquals(ExtensionDescriptor.FieldType.URI, descriptor.getConfigs().get("uri").getFieldType());
            assertEquals(ExtensionDescriptor.FieldType.BOOLEAN, descriptor.getConfigs().get("appendActions").getFieldType());
        }
    }
}
