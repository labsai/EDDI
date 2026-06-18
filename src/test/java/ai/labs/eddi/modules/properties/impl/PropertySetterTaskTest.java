/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.*;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
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
import org.mockito.ArgumentCaptor;

import java.net.URI;
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
        assertEquals("ai.labs.property", task.getId().name());
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

        @Test
        @DisplayName("CATCH_ANY_INPUT_AS_PROPERTY with missing input:initial — should not NPE")
        void catchAnyInput_missingInputInitial() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

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

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(1);

            var previousStep = mock(IWritableConversationStep.class);
            when(previousSteps.get(0)).thenReturn(previousStep);

            var prevActionsData = mock(IData.class);
            when(previousStep.getLatestData("actions")).thenReturn(prevActionsData);
            when(prevActionsData.getResult()).thenReturn(List.of("CATCH_ANY_INPUT_AS_PROPERTY"));

            // input:initial is missing (blank message was sent)
            when(currentStep.getLatestData("input:initial")).thenReturn(null);

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties, never()).put(eq("user_input"), any(Property.class));
        }

        @Test
        @DisplayName("CATCH_ANY_INPUT_AS_PROPERTY with null result — should not NPE")
        void catchAnyInput_nullResult() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

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

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(1);

            var previousStep = mock(IWritableConversationStep.class);
            when(previousSteps.get(0)).thenReturn(previousStep);

            var prevActionsData = mock(IData.class);
            when(previousStep.getLatestData("actions")).thenReturn(prevActionsData);
            when(prevActionsData.getResult()).thenReturn(List.of("CATCH_ANY_INPUT_AS_PROPERTY"));

            // input:initial exists but getResult() returns null
            var inputData = mock(IData.class);
            when(currentStep.getLatestData("input:initial")).thenReturn(inputData);
            when(inputData.getResult()).thenReturn(null);

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties, never()).put(eq("user_input"), any(Property.class));
        }

        @Test
        @DisplayName("fromObjectPath with String value — templates and stores as Property")
        void fromObjectPathStringValue() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("extract"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            templateDataObjects.put("context", Map.of("result", "theValue"));
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("extracted");
            instruction.setFromObjectPath("context.result");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("extract"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("extracted"), captor.capture());
            assertEquals("theValue", captor.getValue().getValueString());
        }

        @Test
        @DisplayName("fromObjectPath with Map value — stores as Property with Map value")
        void fromObjectPathMapValue() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("extract"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var innerMap = new LinkedHashMap<String, Object>();
            innerMap.put("a", "1");
            innerMap.put("b", "2");
            var templateDataObjects = new HashMap<String, Object>();
            templateDataObjects.put("context", Map.of("data", innerMap));
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("mapProp");
            instruction.setFromObjectPath("context.data");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("extract"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("mapProp"), captor.capture());
            assertEquals(innerMap, captor.getValue().getValueObject());
        }

        @Test
        @DisplayName("fromObjectPath with List value — stores as Property with List value")
        void fromObjectPathListValue() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("extract"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var innerList = new ArrayList<>(List.of("x", "y", "z"));
            var templateDataObjects = new HashMap<String, Object>();
            templateDataObjects.put("context", Map.of("items", innerList));
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("listProp");
            instruction.setFromObjectPath("context.items");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("extract"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("listProp"), captor.capture());
            assertEquals(innerList, captor.getValue().getValueList());
        }

        @Test
        @DisplayName("fromObjectPath with Integer value — stores as Property with Integer value")
        void fromObjectPathIntegerValue() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("extract"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            templateDataObjects.put("context", Map.of("count", 99));
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("intProp");
            instruction.setFromObjectPath("context.count");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("extract"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("intProp"), captor.capture());
            assertEquals(99, captor.getValue().getValueInt());
        }

        @Test
        @DisplayName("fromObjectPath with Float value — stores as Property with Float value")
        void fromObjectPathFloatValue() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("extract"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            templateDataObjects.put("context", Map.of("rate", 3.14f));
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("floatProp");
            instruction.setFromObjectPath("context.rate");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("extract"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("floatProp"), captor.capture());
            assertEquals(3.14f, captor.getValue().getValueFloat());
        }

        @Test
        @DisplayName("fromObjectPath with Boolean value — stores as Property with Boolean value")
        void fromObjectPathBooleanValue() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("extract"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            templateDataObjects.put("context", Map.of("active", Boolean.TRUE));
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("boolProp");
            instruction.setFromObjectPath("context.active");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("extract"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("boolProp"), captor.capture());
            assertEquals(Boolean.TRUE, captor.getValue().getValueBoolean());
        }

        @Test
        @DisplayName("fromObjectPath with toObjectPath set — calls PathNavigator.setValue")
        void fromObjectPathWithToObjectPath() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("copy"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            // Build a mutable nested map for PathNavigator.setValue to write into
            var targetMap = new HashMap<String, Object>();
            var templateDataObjects = new HashMap<String, Object>();
            templateDataObjects.put("context", Map.of("source", "sourceValue"));
            templateDataObjects.put("target", targetMap);
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("copied");
            instruction.setFromObjectPath("context.source");
            instruction.setToObjectPath("target.dest");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("copy"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            // When toObjectPath is set, should NOT put into conversationProperties directly
            verify(conversationProperties, never()).put(eq("copied"), any(Property.class));
            // Instead, PathNavigator.setValue should have written to the target map
            assertEquals("sourceValue", targetMap.get("dest"));
        }

        @Test
        @DisplayName("scope=secret — auto-vaults plaintext and stores vault reference")
        void scopeSecretAutoVaults() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("store_secret"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(memory.getAgentId()).thenReturn("agent123");

            // No input data matching the secret, so scrubbing is skipped
            when(currentStep.getLatestData("input:initial")).thenReturn(null);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("apiKey");
            instruction.setValueString("my-secret-key-123");
            instruction.setScope(Property.Scope.secret);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("store_secret"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            // Verify secretProvider.store was called
            verify(secretProvider).store(any(), eq("my-secret-key-123"), anyString(), anyList());

            // Verify the property is stored with conversation scope (not secret) and vault
            // ref
            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("apiKey"), captor.capture());
            var storedProperty = captor.getValue();
            assertEquals(Property.Scope.conversation, storedProperty.getScope());
            assertTrue(storedProperty.getValueString().startsWith("${vault:"));
        }

        @Test
        @DisplayName("scope=secret vault fails — logs error and returns plaintext")
        void scopeSecretVaultFails() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("store_secret"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(memory.getAgentId()).thenReturn("agent456");

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Make vault storage fail
            doThrow(new ISecretProvider.SecretProviderException("Vault unavailable"))
                    .when(secretProvider).store(any(), anyString(), anyString(), anyList());

            var instruction = new PropertyInstruction();
            instruction.setName("apiKey");
            instruction.setValueString("plaintext-secret");
            instruction.setScope(Property.Scope.secret);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("store_secret"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            // Should not throw — graceful degradation
            assertDoesNotThrow(() -> task.execute(memory, propertySetter));

            // Verify property is stored with plaintext (degraded mode)
            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("apiKey"), captor.capture());
            assertEquals("plaintext-secret", captor.getValue().getValueString());
        }

        @Test
        @DisplayName("valueFloat — stores Float property via instruction")
        void valueFloat() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("set_rate"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("rate");
            instruction.setValueFloat(9.99f);
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("set_rate"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("rate"), captor.capture());
            assertEquals(9.99f, captor.getValue().getValueFloat());
        }

        @Test
        @DisplayName("valueBoolean — stores Boolean property via instruction")
        void valueBoolean() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("set_flag"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var instruction = new PropertyInstruction();
            instruction.setName("enabled");
            instruction.setValueBoolean(true);
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("set_flag"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("enabled"), captor.capture());
            assertEquals(true, captor.getValue().getValueBoolean());
        }

        @Test
        @DisplayName("valueList — stores List property via instruction")
        void valueList() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("set_tags"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(inv -> inv.getArgument(0));

            var tagList = List.<Object>of("tag1", "tag2", "tag3");
            var instruction = new PropertyInstruction();
            instruction.setName("tags");
            instruction.setValueList(tagList);
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("set_tags"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            task.execute(memory, propertySetter);

            var captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties).put(eq("tags"), captor.capture());
            assertEquals(tagList, captor.getValue().getValueList());
        }

        @Test
        @DisplayName("template processing failure — wraps in LifecycleException")
        void templateProcessingFailureThrowsLifecycleException() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getAllData("context")).thenReturn(null);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("fail"));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);

            var templateDataObjects = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
            // First call to processTemplate (for name) succeeds, second (for value) fails
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("resolvedName")
                    .thenThrow(new ITemplatingEngine.TemplateEngineException("Bad template", null));

            var instruction = new PropertyInstruction();
            instruction.setName("broken");
            instruction.setValueString("{{invalid}}");
            instruction.setScope(Property.Scope.conversation);
            instruction.setOverride(true);

            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("fail"));
            setOnActions.setSetProperties(List.of(instruction));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertThrows(LifecycleException.class, () -> task.execute(memory, propertySetter));
        }

        @Test
        @DisplayName("CATCH_ANY_INPUT_AS_PROPERTY with empty input — should NOT add property")
        void catchAnyInputEmptyInput() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

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
            when(inputData.getResult()).thenReturn(""); // empty input

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            task.execute(memory, propertySetter);

            // Should NOT store user_input property for empty input
            verify(conversationProperties, never()).put(eq("user_input"), any(Property.class));
        }

        @Test
        @DisplayName("previous step has no actions data — should not throw")
        void previousStepNullActionsData() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

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

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(1);

            var previousStep = mock(IWritableConversationStep.class);
            when(previousSteps.get(0)).thenReturn(previousStep);
            // Previous step has null actions data
            when(previousStep.getLatestData("actions")).thenReturn(null);

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties, never()).put(eq("user_input"), any(Property.class));
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

        @Test
        @DisplayName("setOnActions with actionsObj as String — wraps in list")
        void actionsAsString() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("setOnActions", List.of(
                    Map.of("actions", "single_action",
                            "setProperties", List.of(
                                    Map.of("name", "prop", "valueString", "val", "scope", "conversation")))));

            var result = task.configure(config, Map.of());

            assertNotNull(result);
            assertInstanceOf(IPropertySetter.class, result);
        }

        @Test
        @DisplayName("URI based config loading — calls resourceClientLibrary.getResource")
        void uriBasedConfigLoading() throws Exception {
            var propertySetterConfig = new PropertySetterConfiguration();
            var setOnActions = new SetOnActions();
            setOnActions.setActions(List.of("remote_action"));
            setOnActions.setSetProperties(List.of());
            propertySetterConfig.setSetOnActions(List.of(setOnActions));

            when(resourceClientLibrary.getResource(any(URI.class), eq(PropertySetterConfiguration.class)))
                    .thenReturn(propertySetterConfig);

            var config = new HashMap<String, Object>();
            config.put("uri", "eddi://ai.labs.propertysetter/propertysetter/abc123?version=1");

            var result = task.configure(config, Map.of());

            assertNotNull(result);
            assertInstanceOf(IPropertySetter.class, result);
            verify(resourceClientLibrary).getResource(any(URI.class), eq(PropertySetterConfiguration.class));
        }

        @Test
        @DisplayName("setOnActions with valueObject — parses correctly")
        void valueObjectConfig() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("setOnActions", List.of(
                    Map.of("actions", List.of("action1"),
                            "setProperties", List.of(
                                    Map.of("name", "obj_prop",
                                            "valueObject", Map.of("nested", "value"),
                                            "scope", "conversation")))));

            var result = task.configure(config, Map.of());

            assertNotNull(result);
        }

        @Test
        @DisplayName("setOnActions with valueInt — parses correctly")
        void valueIntConfig() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("setOnActions", List.of(
                    Map.of("actions", List.of("action1"),
                            "setProperties", List.of(
                                    Map.of("name", "int_prop", "valueInt", 42, "scope", "conversation")))));

            var result = task.configure(config, Map.of());

            assertNotNull(result);
        }

        @Test
        @DisplayName("setOnActions with valueFloat — parses correctly")
        void valueFloatConfig() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("setOnActions", List.of(
                    Map.of("actions", List.of("action1"),
                            "setProperties", List.of(
                                    Map.of("name", "float_prop", "valueFloat", 3.14f, "scope", "conversation")))));

            var result = task.configure(config, Map.of());

            assertNotNull(result);
        }

        @Test
        @DisplayName("setOnActions with valueBoolean — parses correctly")
        void valueBooleanConfig() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("setOnActions", List.of(
                    Map.of("actions", List.of("action1"),
                            "setProperties", List.of(
                                    Map.of("name", "bool_prop", "valueBoolean", true, "scope", "conversation")))));

            var result = task.configure(config, Map.of());

            assertNotNull(result);
        }

        @Test
        @DisplayName("setOnActions with valueList — parses correctly")
        void valueListConfig() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("setOnActions", List.of(
                    Map.of("actions", List.of("action1"),
                            "setProperties", List.of(
                                    Map.of("name", "list_prop",
                                            "valueList", List.of("a", "b", "c"),
                                            "scope", "conversation")))));

            var result = task.configure(config, Map.of());

            assertNotNull(result);
        }

        @Test
        @DisplayName("setOnActions with fromObjectPath — parses correctly")
        void fromObjectPathConfig() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("setOnActions", List.of(
                    Map.of("actions", List.of("action1"),
                            "setProperties", List.of(
                                    Map.of("name", "path_prop",
                                            "fromObjectPath", "memory.current.input",
                                            "scope", "longTerm")))));

            var result = task.configure(config, Map.of());

            assertNotNull(result);
        }

        @Test
        @DisplayName("ServiceException wraps in WorkflowConfigurationException")
        void serviceExceptionWrapsInWorkflowConfigurationException() throws Exception {
            when(resourceClientLibrary.getResource(any(URI.class), eq(PropertySetterConfiguration.class)))
                    .thenThrow(new ServiceException("Connection refused"));

            var config = new HashMap<String, Object>();
            config.put("uri", "eddi://ai.labs.propertysetter/propertysetter/abc123?version=1");

            var ex = assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Map.of()));
            assertTrue(ex.getMessage().contains("Error while fetching PropertySetterConfiguration"));
            assertTrue(ex.getMessage().contains("Connection refused"));
        }
    }
}
