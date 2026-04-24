/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.*;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.properties.IPropertySetter;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("PropertySetterTask Tests")
class PropertySetterTaskTest {

    private IExpressionProvider expressionProvider;
    private IMemoryItemConverter memoryItemConverter;
    private ITemplatingEngine templatingEngine;
    private IDataFactory dataFactory;
    private IResourceClientLibrary resourceClientLibrary;
    private ISecretProvider secretProvider;
    private PropertySetterTask task;

    @BeforeEach
    void setUp() {
        expressionProvider = mock(IExpressionProvider.class);
        memoryItemConverter = mock(IMemoryItemConverter.class);
        templatingEngine = mock(ITemplatingEngine.class);
        dataFactory = mock(IDataFactory.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        secretProvider = mock(ISecretProvider.class);

        task = new PropertySetterTask(expressionProvider, memoryItemConverter,
                templatingEngine, dataFactory, resourceClientLibrary,
                new ObjectMapper(), secretProvider);
    }

    @Test
    @DisplayName("getId returns correct ID")
    void getId() {
        assertEquals("ai.labs.property", task.getId());
    }

    @Test
    @DisplayName("getType returns 'properties'")
    void getType() {
        assertEquals("properties", task.getType());
    }

    @Test
    @DisplayName("getExtensionDescriptor returns valid descriptor")
    void getExtensionDescriptor() {
        var descriptor = task.getExtensionDescriptor();
        assertNotNull(descriptor);
        assertEquals("Property Extraction", descriptor.getDisplayName());
    }

    @Nested
    @DisplayName("execute Tests")
    class ExecuteTests {

        @Test
        @DisplayName("no expressions, no context, no actions — should return early")
        void noData() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);
            when(currentStep.getLatestData("actions")).thenReturn(null);

            var propertySetter = mock(IPropertySetter.class);

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(propertySetter, never()).extractProperties(any());
        }

        @Test
        @DisplayName("actions with setOnActions — sets conversation properties")
        void actionsWithSetOnActions() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("greet"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("greeting");
            instruction.setValueString("hello");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("greet"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));

            verify(conversationProperties).put(eq("greeting"), any(Property.class));
        }

        @Test
        @DisplayName("wildcard action — matches all")
        void wildcardAction() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("any_action"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("captured");
            instruction.setValueString("yes");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("*")); // wildcard
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties).put(eq("captured"), any(Property.class));
        }

        @Test
        @DisplayName("override=false and property exists — should NOT overwrite")
        void noOverride() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("set_name"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(conversationProperties.containsKey("userName")).thenReturn(true); // already exists

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("userName");
            instruction.setValueString("new_name");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(false); // do not override

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("set_name"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties, never()).put(eq("userName"), any(Property.class));
        }

        @Test
        @DisplayName("valueObject — stores Map property")
        void valueObject() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("set_config"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("config");
            instruction.setValueObject(Map.of("key1", "val1", "key2", "val2"));
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("set_config"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties).put(eq("config"), any(Property.class));
        }

        @Test
        @DisplayName("valueInt — stores Integer property")
        void valueInt() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("set_count"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("count");
            instruction.setValueInt(42);
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("set_count"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties).put(eq("count"), any(Property.class));
        }

        @Test
        @DisplayName("context with properties expressions — extracts expressions")
        void contextProperties() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getLatestData("actions")).thenReturn(null);

            var context = new Context();
            context.setType(Context.ContextType.expressions);
            context.setValue("property(language, en)");

            var contextData = mock(IData.class);
            when(contextData.getKey()).thenReturn("context:properties");
            when(contextData.getResult()).thenReturn(context);
            when(currentStep.getAllData("context")).thenReturn(List.of(contextData));

            when(expressionProvider.parseExpressions("property(language, en)"))
                    .thenReturn(new Expressions());

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(expressionProvider).parseExpressions("property(language, en)");
        }

        @Test
        @DisplayName("CATCH_ANY_INPUT_AS_PROPERTY in previous step — captures user input")
        void catchAnyInput() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            // Need at least one non-null data source to bypass early return guard
            var expressionsData = mock(IData.class);
            when(expressionsData.getResult()).thenReturn("");
            when(currentStep.getLatestData("expressions:parsed")).thenReturn(expressionsData);
            when(currentStep.getAllData("context")).thenReturn(null);
            when(currentStep.getLatestData("actions")).thenReturn(null);

            when(expressionProvider.parseExpressions("")).thenReturn(new Expressions());

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);

            // Previous step has CATCH_ANY_INPUT_AS_PROPERTY
            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(1);

            var previousStep = mock(IWritableConversationStep.class);
            when(previousSteps.get(0)).thenReturn(previousStep);

            var prevActionsData = mock(IData.class);
            when(previousStep.getLatestData("actions")).thenReturn(prevActionsData);
            when(prevActionsData.getResult()).thenReturn(List.of("CATCH_ANY_INPUT_AS_PROPERTY"));

            var inputData = mock(IData.class);
            when(currentStep.getLatestData("input:initial")).thenReturn(inputData);
            when(inputData.getResult()).thenReturn("John");

            var outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), anyList(), eq(true))).thenReturn(outputData);

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties).put(eq("user_input"), any(Property.class));
        }
    }

    @Nested
    @DisplayName("configure Tests")
    class ConfigureTests {

        @Test
        @DisplayName("setOnActions in raw config — parses correctly")
        void rawConfig() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("setOnActions", List.of(
                    Map.of("actions", List.of("greet"),
                            "setProperties", List.of(
                                    Map.of("name", "greeting", "valueString", "hello", "scope", "conversation")))));

            var result = task.configure(config, Map.of());

            assertNotNull(result);
            assertInstanceOf(IPropertySetter.class, result);
        }

        @Test
        @DisplayName("no setOnActions and no URI — returns empty PropertySetter")
        void noConfig() throws Exception {
            var config = new HashMap<String, Object>();

            var result = task.configure(config, Map.of());

            assertNotNull(result);
            assertInstanceOf(IPropertySetter.class, result);
        }
    }
}
