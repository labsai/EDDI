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

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("PropertySetterTask Extended Branch Coverage Tests")
@SuppressWarnings("unchecked")
class PropertySetterTaskExtendedTest {

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

    @Nested
    @DisplayName("execute — fromObjectPath branches")
    class FromObjectPathBranches {

        @Test
        @DisplayName("fromObjectPath with Map value — stores as Map property")
        void fromObjectPathMap() throws Exception {
            var env = setupExecuteEnv("set_map", "mapProp");
            var instruction = env.instruction;
            instruction.setFromObjectPath("context.obj");
            instruction.setOverride(true);

            Map<String, Object> innerMap = new LinkedHashMap<>();
            innerMap.put("key", "value");
            env.templateData.put("context", Map.of("obj", innerMap));

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(env.conversationProperties).put(eq("mapProp"), any(Property.class));
        }

        @Test
        @DisplayName("fromObjectPath with List value — stores as List property")
        void fromObjectPathList() throws Exception {
            var env = setupExecuteEnv("set_list", "listProp");
            var instruction = env.instruction;
            instruction.setFromObjectPath("context.arr");
            instruction.setOverride(true);

            env.templateData.put("context", Map.of("arr", List.of("a", "b")));

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(env.conversationProperties).put(eq("listProp"), any(Property.class));
        }

        @Test
        @DisplayName("fromObjectPath with Integer value — stores as Integer property")
        void fromObjectPathInteger() throws Exception {
            var env = setupExecuteEnv("set_int", "intProp");
            var instruction = env.instruction;
            instruction.setFromObjectPath("context.num");
            instruction.setOverride(true);

            env.templateData.put("context", Map.of("num", 42));

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(env.conversationProperties).put(eq("intProp"), any(Property.class));
        }

        @Test
        @DisplayName("fromObjectPath with Float value — stores as Float property")
        void fromObjectPathFloat() throws Exception {
            var env = setupExecuteEnv("set_float", "floatProp");
            var instruction = env.instruction;
            instruction.setFromObjectPath("context.fval");
            instruction.setOverride(true);

            env.templateData.put("context", Map.of("fval", 3.14f));

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(env.conversationProperties).put(eq("floatProp"), any(Property.class));
        }

        @Test
        @DisplayName("fromObjectPath with Boolean value — stores as Boolean property")
        void fromObjectPathBoolean() throws Exception {
            var env = setupExecuteEnv("set_bool", "boolProp");
            var instruction = env.instruction;
            instruction.setFromObjectPath("context.flag");
            instruction.setOverride(true);

            env.templateData.put("context", Map.of("flag", true));

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(env.conversationProperties).put(eq("boolProp"), any(Property.class));
        }

        @Test
        @DisplayName("fromObjectPath with toObjectPath — sets value at target path")
        void fromObjectPathToObjectPath() throws Exception {
            var env = setupExecuteEnv("set_to", "prop");
            var instruction = env.instruction;
            instruction.setFromObjectPath("context.src");
            instruction.setToObjectPath("context.dest");
            instruction.setOverride(true);

            env.templateData.put("context", new HashMap<>(Map.of("src", "value")));

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            // Should NOT put property — toObjectPath routes elsewhere
            verify(env.conversationProperties, never()).put(eq("prop"), any(Property.class));
        }
    }

    @Nested
    @DisplayName("execute — valueList, valueFloat, valueBoolean branches")
    class ValueTypeBranches {

        @Test
        @DisplayName("valueList — stores as List property")
        void valueList() throws Exception {
            var env = setupExecuteEnv("set_list", "listProp");
            env.instruction.setValueList(List.of("x", "y"));
            env.instruction.setOverride(true);

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(env.conversationProperties).put(eq("listProp"), any(Property.class));
        }

        @Test
        @DisplayName("valueFloat — stores as Float property")
        void valueFloat() throws Exception {
            var env = setupExecuteEnv("set_float", "floatProp");
            env.instruction.setValueFloat(2.71f);
            env.instruction.setOverride(true);

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(env.conversationProperties).put(eq("floatProp"), any(Property.class));
        }

        @Test
        @DisplayName("valueBoolean — stores as Boolean property")
        void valueBoolean() throws Exception {
            var env = setupExecuteEnv("set_bool", "boolProp");
            env.instruction.setValueBoolean(true);
            env.instruction.setOverride(true);

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(env.conversationProperties).put(eq("boolProp"), any(Property.class));
        }
    }

    @Nested
    @DisplayName("execute — secret scope branch")
    class SecretScopeBranch {

        @Test
        @DisplayName("scope=secret — auto-vaults the value")
        void secretScope() throws Exception {
            var env = setupExecuteEnv("set_secret", "apiKey");
            env.instruction.setValueString("my-secret-key");
            env.instruction.setScope(Property.Scope.secret);
            env.instruction.setOverride(true);

            when(env.memory.getAgentId()).thenReturn("agent123");
            when(env.conversationProperties.containsKey("tenantId")).thenReturn(false);
            var mockInputData = mock(IData.class);
            when(mockInputData.getResult()).thenReturn("my-secret-key");
            when(env.memory.getCurrentStep().getLatestData("input:initial")).thenReturn(mockInputData);
            when(dataFactory.createData(anyString(), any())).thenReturn(mock(IData.class));

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(secretProvider).store(any(), eq("my-secret-key"), anyString(), anyList());
            verify(env.conversationProperties).put(eq("apiKey"), any(Property.class));
        }

        @Test
        @DisplayName("scope=secret with vault failure — falls back to plaintext")
        void secretScopeVaultFailure() throws Exception {
            var env = setupExecuteEnv("set_secret_fail", "apiKey");
            env.instruction.setValueString("my-secret-key");
            env.instruction.setScope(Property.Scope.secret);
            env.instruction.setOverride(true);

            when(env.memory.getAgentId()).thenReturn("agent123");
            when(env.conversationProperties.containsKey("tenantId")).thenReturn(false);
            doThrow(new ISecretProvider.SecretProviderException("vault error"))
                    .when(secretProvider).store(any(), anyString(), anyString(), anyList());

            assertDoesNotThrow(() -> task.execute(env.memory, env.propertySetter));
            verify(env.conversationProperties).put(eq("apiKey"), any(Property.class));
        }
    }

    @Nested
    @DisplayName("execute — CATCH_ANY_INPUT_AS_PROPERTY with empty input")
    class CatchAnyInputBranches {

        @Test
        @DisplayName("empty initial input — does NOT add user_input property")
        void emptyInput() throws Exception {
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
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

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
            when(inputData.getResult()).thenReturn(""); // empty

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties, never()).put(eq("user_input"), any(Property.class));
        }

        @Test
        @DisplayName("previous step actions null — no crash")
        void prevActionsNull() throws Exception {
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
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(1);
            var previousStep = mock(IWritableConversationStep.class);
            when(previousSteps.get(0)).thenReturn(previousStep);
            when(previousStep.getLatestData("actions")).thenReturn(null);

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
        }

        @Test
        @DisplayName("previous step has actions but without CATCH_ANY — no capture")
        void prevActionsWithoutCatch() throws Exception {
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
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(1);
            var previousStep = mock(IWritableConversationStep.class);
            when(previousSteps.get(0)).thenReturn(previousStep);
            var prevActionsData = mock(IData.class);
            when(previousStep.getLatestData("actions")).thenReturn(prevActionsData);
            when(prevActionsData.getResult()).thenReturn(List.of("OTHER_ACTION"));

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(conversationProperties, never()).put(eq("user_input"), any(Property.class));
        }
    }

    @Nested
    @DisplayName("execute — LifecycleException from template engine")
    class TemplateExceptionBranch {

        @Test
        @DisplayName("template engine throws — wraps in LifecycleException")
        void templateThrows() throws Exception {
            var env = setupExecuteEnv("set_err", "prop");
            env.instruction.setValueString("{{invalid}}");
            env.instruction.setOverride(true);

            when(templatingEngine.processTemplate(eq("prop"), anyMap()))
                    .thenReturn("prop");
            when(templatingEngine.processTemplate(eq("{{invalid}}"), anyMap()))
                    .thenThrow(new ITemplatingEngine.TemplateEngineException("parse error", new RuntimeException("cause")));

            assertThrows(LifecycleException.class,
                    () -> task.execute(env.memory, env.propertySetter));
        }
    }

    @Nested
    @DisplayName("configure — URI branch")
    class ConfigureUriBranch {

        @Test
        @DisplayName("config with eddi URI — loads from resource library")
        void eddiUri() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("uri", "eddi://ai.labs.propertysetter/propertysetter/12345");

            var setterConfig = new PropertySetterConfiguration();
            setterConfig.setSetOnActions(new LinkedList<>());
            when(resourceClientLibrary.getResource(any(URI.class), eq(PropertySetterConfiguration.class)))
                    .thenReturn(setterConfig);

            var result = task.configure(config, Map.of());
            assertNotNull(result);
        }

        @Test
        @DisplayName("config with non-eddi URI — skips resource loading")
        void nonEddiUri() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("uri", "http://external.com/config");

            var result = task.configure(config, Map.of());
            assertNotNull(result);
            verify(resourceClientLibrary, never()).getResource(any(), any());
        }

        @Test
        @DisplayName("ServiceException from resource library — throws WorkflowConfigurationException")
        void serviceException() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("uri", "eddi://ai.labs.propertysetter/propertysetter/12345");

            when(resourceClientLibrary.getResource(any(URI.class), eq(PropertySetterConfiguration.class)))
                    .thenThrow(new ServiceException("load error"));

            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Map.of()));
        }

        @Test
        @DisplayName("null URI value — skips loading")
        void nullUriValue() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("uri", null);

            var result = task.configure(config, Map.of());
            assertNotNull(result);
        }

        @Test
        @DisplayName("empty URI value — skips loading")
        void emptyUriValue() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("uri", "");

            var result = task.configure(config, Map.of());
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("configure — context with non-properties key — ignored")
    class ContextIgnored {

        @Test
        @DisplayName("context key not starting with 'properties' — skipped")
        void nonPropertiesContext() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getLatestData("actions")).thenReturn(null);

            var context = new Context();
            context.setType(Context.ContextType.expressions);
            context.setValue("expr()");

            var contextData = mock(IData.class);
            when(contextData.getKey()).thenReturn("context:other");
            when(contextData.getResult()).thenReturn(context);
            when(currentStep.getAllData("context")).thenReturn(List.of(contextData));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(expressionProvider, never()).parseExpressions(anyString());
        }

        @Test
        @DisplayName("context with non-expressions type — skipped")
        void nonExpressionsType() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
            when(currentStep.getLatestData("actions")).thenReturn(null);

            var context = new Context();
            context.setType(Context.ContextType.object);
            context.setValue("some object");

            var contextData = mock(IData.class);
            when(contextData.getKey()).thenReturn("context:properties");
            when(contextData.getResult()).thenReturn(context);
            when(currentStep.getAllData("context")).thenReturn(List.of(contextData));

            var conversationProperties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            var propertySetter = mock(IPropertySetter.class);
            when(propertySetter.getSetOnActionsList()).thenReturn(List.of());
            when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

            assertDoesNotThrow(() -> task.execute(memory, propertySetter));
            verify(expressionProvider, never()).parseExpressions(anyString());
        }
    }

    // --- Helpers ---

    private record ExecuteEnv(IConversationMemory memory, IConversationProperties conversationProperties,
            Map<String, Object> templateData, PropertyInstruction instruction,
            IPropertySetter propertySetter) {
    }

    private ExecuteEnv setupExecuteEnv(String action, String propName) throws Exception {
        var memory = mock(IConversationMemory.class);
        var currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        when(currentStep.getLatestData("expressions:parsed")).thenReturn(null);
        when(currentStep.getAllData("context")).thenReturn(null);

        var actionsData = mock(IData.class);
        when(currentStep.getLatestData("actions")).thenReturn(actionsData);
        when(actionsData.getResult()).thenReturn(List.of(action));

        var conversationProperties = mock(IConversationProperties.class);
        when(memory.getConversationProperties()).thenReturn(conversationProperties);
        when(memory.getAgentId()).thenReturn("agent123");

        var templateDataObjects = new HashMap<String, Object>();
        when(memoryItemConverter.convert(memory)).thenReturn(templateDataObjects);
        when(templatingEngine.processTemplate(anyString(), anyMap()))
                .thenAnswer(inv -> inv.getArgument(0));

        var instruction = new PropertyInstruction();
        instruction.setName(propName);
        instruction.setScope(Property.Scope.conversation);

        var setOnActions = new SetOnActions();
        setOnActions.setActions(List.of(action));
        setOnActions.setSetProperties(List.of(instruction));

        var propertySetter = mock(IPropertySetter.class);
        when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
        when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

        var previousSteps = mock(IConversationStepStack.class);
        when(memory.getPreviousSteps()).thenReturn(previousSteps);
        when(previousSteps.size()).thenReturn(0);

        return new ExecuteEnv(memory, conversationProperties, templateDataObjects, instruction, propertySetter);
    }
}
