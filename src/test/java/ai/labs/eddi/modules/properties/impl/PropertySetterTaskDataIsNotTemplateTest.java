/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.properties.IPropertySetter;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A value a property instruction reads through {@code fromObjectPath} —
 * typically the user's message — is data and must be stored as it is, with a
 * real template engine in the loop. Only author-written {@code valueString}
 * fields are templates.
 */
@DisplayName("PropertySetterTask stores path values verbatim")
class PropertySetterTaskDataIsNotTemplateTest {

    private IMemoryItemConverter memoryItemConverter;
    private PropertySetterTask task;
    private IConversationMemory memory;
    private IConversationProperties conversationProperties;
    private Map<String, Object> templateData;

    @BeforeEach
    void setUp() {
        memoryItemConverter = mock(IMemoryItemConverter.class);
        var templatingEngine = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());
        task = new PropertySetterTask(mock(IExpressionProvider.class), memoryItemConverter, templatingEngine, mock(IDataFactory.class),
                mock(IResourceClientLibrary.class), new ObjectMapper(), mock(ISecretProvider.class));

        memory = mock(IConversationMemory.class);
        var currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        IData<Object> actions = mock(IData.class);
        when(actions.getResult()).thenReturn(List.of("capture"));
        when(currentStep.getLatestData("actions")).thenReturn(actions);

        conversationProperties = mock(IConversationProperties.class);
        when(memory.getConversationProperties()).thenReturn(conversationProperties);
        var previousSteps = mock(IConversationStepStack.class);
        when(memory.getPreviousSteps()).thenReturn(previousSteps);

        templateData = new HashMap<>();
        templateData.put("properties", Map.of("apiToken", "tok-7f3a"));
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);
    }

    private IPropertySetter setterWith(PropertyInstruction instruction) {
        var setOnActions = new SetOnActions();
        setOnActions.setActions(List.of("capture"));
        setOnActions.setSetProperties(List.of(instruction));
        var propertySetter = mock(IPropertySetter.class);
        when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
        when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
        return propertySetter;
    }

    private static PropertyInstruction instruction(String name) {
        var instruction = new PropertyInstruction();
        instruction.setName(name);
        instruction.setScope(Property.Scope.conversation);
        instruction.setOverride(true);
        return instruction;
    }

    private String storedValue(String name) {
        var captor = ArgumentCaptor.forClass(Property.class);
        verify(conversationProperties).put(eq(name), captor.capture());
        return captor.getValue().getValueString();
    }

    @Test
    @DisplayName("template syntax in the user's message is stored literally")
    void userInputIsStoredVerbatim() throws Exception {
        String userInput = "my name is {properties.apiToken} {#for i in 3}x{/for}";
        templateData.put("memory", Map.of("current", Map.of("input", userInput)));
        var instruction = instruction("userName");
        instruction.setFromObjectPath("memory.current.input");

        task.execute(memory, setterWith(instruction));

        assertEquals(userInput, storedValue("userName"));
    }

    @Test
    @DisplayName("an author-written valueString is still a template")
    void valueStringIsStillRendered() throws Exception {
        var instruction = instruction("greeting");
        instruction.setValueString("token is {properties.apiToken}");

        task.execute(memory, setterWith(instruction));

        assertEquals("token is tok-7f3a", storedValue("greeting"));
    }
}
