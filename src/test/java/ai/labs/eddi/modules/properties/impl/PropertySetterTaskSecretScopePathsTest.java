/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.properties.IPropertySetter;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C2b — {@code scope: "secret"} is honoured on every property path of the
 * property setter, or the value is refused. The {@code fromObjectPath} branch
 * and the typed value fields used to store the value as a plaintext property,
 * scope notwithstanding.
 */
class PropertySetterTaskSecretScopePathsTest {

    private static final String TOKEN = "tok-live-aaaaaaaaaaaaaaaa";

    private ISecretProvider secretProvider;
    private PropertySetterTask task;
    private ConversationMemory memory;
    private Map<String, Object> templateData;

    @BeforeEach
    void setUp() {
        var expressionProvider = mock(IExpressionProvider.class);
        when(expressionProvider.parseExpressions(anyString())).thenReturn(new Expressions());
        var memoryItemConverter = mock(IMemoryItemConverter.class);
        var templatingEngine = mock(ITemplatingEngine.class);
        secretProvider = mock(ISecretProvider.class);

        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        templateData = new HashMap<>();
        var response = new LinkedHashMap<String, Object>();
        response.put("access_token", TOKEN);
        response.put("claims", Map.of("sub", "user-1"));
        response.put("expires_in", 3600);
        templateData.put("tokenResponse", response);
        when(memoryItemConverter.convert(any())).thenReturn(templateData);
        try {
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));
        } catch (ITemplatingEngine.TemplateEngineException e) {
            throw new IllegalStateException(e);
        }

        task = new PropertySetterTask(expressionProvider, memoryItemConverter, templatingEngine, new DataFactory(),
                mock(IResourceClientLibrary.class), new ObjectMapper(),
                new SecretPropertyVault(secretProvider, mock(SecretResolver.class), new DataFactory()));

        var step = memory.getCurrentStep();
        step.storeData(new Data<>("actions", List.of("store_secret")));
        // Where a token fetched by an httpcall sits: the saved response in the step.
        step.storeData(new Data<>("httpCalls:tokenResponse", Map.of("access_token", TOKEN)));
    }

    private static IPropertySetter setterFor(PropertyInstruction instruction) {
        var setOnActions = new SetOnActions();
        setOnActions.setActions(List.of("store_secret"));
        setOnActions.setSetProperties(List.of(instruction));
        var propertySetter = mock(IPropertySetter.class);
        when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
        when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
        return propertySetter;
    }

    private static PropertyInstruction secretInstruction(String name) {
        var instruction = new PropertyInstruction();
        instruction.setName(name);
        instruction.setScope(Scope.secret);
        instruction.setOverride(true);
        return instruction;
    }

    @Test
    @DisplayName("fromObjectPath + scope secret: the looked-up string is vaulted, never stored in plaintext")
    void fromObjectPathStringIsVaulted() throws Exception {
        var instruction = secretInstruction("accessToken");
        instruction.setFromObjectPath("tokenResponse.access_token");

        task.execute(memory, setterFor(instruction));

        Property stored = memory.getConversationProperties().get("accessToken");
        assertEquals("${vault:agent-1.aabbccddeeff112233445566.accessToken}", stored.getValueString());
        assertEquals(Scope.conversation, stored.getScope());
        assertEquals(Boolean.TRUE, stored.getAutoVaulted());
        verify(secretProvider).store(any(), eq(TOKEN), anyString(), anyList());
        // The saved response the token came from is scrubbed from the step too.
        assertFalse(String.valueOf(memory.getCurrentStep().getLatestData("httpCalls:tokenResponse").getResult()).contains(TOKEN));
    }

    @Test
    @DisplayName("fromObjectPath + scope secret on a map: refused, no property, nothing vaulted")
    void fromObjectPathMapIsRefused() throws Exception {
        var instruction = secretInstruction("claims");
        instruction.setFromObjectPath("tokenResponse.claims");

        var e = assertThrows(LifecycleException.class, () -> task.execute(memory, setterFor(instruction)));

        assertTrue(e.getMessage().contains("only string values can be vaulted"), e.getMessage());
        assertNull(memory.getConversationProperties().get("claims"));
        verify(secretProvider, never()).store(any(), anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("fromObjectPath + scope secret on a number: refused")
    void fromObjectPathNumberIsRefused() {
        var instruction = secretInstruction("expiresIn");
        instruction.setFromObjectPath("tokenResponse.expires_in");

        assertThrows(LifecycleException.class, () -> task.execute(memory, setterFor(instruction)));
        assertNull(memory.getConversationProperties().get("expiresIn"));
    }

    @Test
    @DisplayName("valueObject / valueInt + scope secret: refused instead of stored as plaintext")
    void typedValuesAreRefused() {
        var objectInstruction = secretInstruction("creds");
        objectInstruction.setValueObject(Map.of("password", "hunter2hunter2"));
        assertThrows(LifecycleException.class, () -> task.execute(memory, setterFor(objectInstruction)));
        assertNull(memory.getConversationProperties().get("creds"));

        var intInstruction = secretInstruction("pin");
        intInstruction.setValueInt(482915);
        assertThrows(LifecycleException.class, () -> task.execute(memory, setterFor(intInstruction)));
        assertNull(memory.getConversationProperties().get("pin"));
    }

    @Test
    @DisplayName("fromObjectPath without scope secret keeps its old behaviour")
    void fromObjectPathWithoutSecretUnchanged() throws Exception {
        var instruction = new PropertyInstruction();
        instruction.setName("claims");
        instruction.setFromObjectPath("tokenResponse.claims");
        instruction.setScope(Scope.conversation);
        instruction.setOverride(true);

        task.execute(memory, setterFor(instruction));

        assertEquals(Map.of("sub", "user-1"), memory.getConversationProperties().get("claims").getValueObject());
        verify(secretProvider, never()).store(any(), anyString(), anyString(), anyList());
    }
}
