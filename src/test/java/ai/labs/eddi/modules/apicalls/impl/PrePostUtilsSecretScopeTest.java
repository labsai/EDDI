/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C2b — the pre-request / post-response property instructions of httpcalls, MCP
 * calls and LLM tasks honour {@code scope: "secret"}. They used to store the
 * value as a plaintext property under that scope.
 */
class PrePostUtilsSecretScopeTest {

    private static final String TOKEN = "tok-live-0123456789abcdef";

    private ISecretProvider secretProvider;
    private IJsonSerialization jsonSerialization;
    private PrePostUtils prePostUtils;
    private ConversationMemory memory;
    private Map<String, Object> templateData;

    @BeforeEach
    void setUp() throws Exception {
        secretProvider = mock(ISecretProvider.class);
        jsonSerialization = mock(IJsonSerialization.class);
        var templatingEngine = mock(ITemplatingEngine.class);
        when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));
        prePostUtils = new PrePostUtils(jsonSerialization, mock(IMemoryItemConverter.class), templatingEngine, new DataFactory(),
                new SecretPropertyVault(secretProvider, mock(SecretResolver.class), new DataFactory()));
        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        templateData = new HashMap<>();
        templateData.put("tokenResponse", Map.of("access_token", TOKEN, "claims", Map.of("sub", "u")));
    }

    private static PropertyInstruction secret(String name, String path) {
        var instruction = new PropertyInstruction();
        instruction.setName(name);
        instruction.setFromObjectPath(path);
        instruction.setScope(Scope.secret);
        return instruction;
    }

    @Test
    @DisplayName("a post-response secret instruction stores the vault reference, marked, never the token")
    void secretIsVaulted() throws Exception {
        prePostUtils.executePropertyInstructions(List.of(secret("accessToken", "tokenResponse.access_token")), 200, false, memory, templateData);

        Property stored = memory.getConversationProperties().get("accessToken");
        assertEquals("${vault:agent-1.aabbccddeeff112233445566.accessToken}", stored.getValueString());
        assertEquals(Scope.conversation, stored.getScope());
        assertEquals(Boolean.TRUE, stored.getAutoVaulted());
        verify(secretProvider).store(any(), eq(TOKEN), anyString(), anyList());
    }

    @Test
    @DisplayName("a vault failure leaves no property at all — in particular not a plaintext one")
    void vaultFailureStoresNothing() throws Exception {
        doThrow(new ISecretProvider.SecretProviderException("vault disabled")).when(secretProvider).store(any(), anyString(), anyString(),
                anyList());

        prePostUtils.executePropertyInstructions(List.of(secret("accessToken", "tokenResponse.access_token")), 200, false, memory, templateData);

        assertNull(memory.getConversationProperties().get("accessToken"));
    }

    @Test
    @DisplayName("a secret instruction that yields a non-string is refused")
    void nonStringRefused() throws Exception {
        var instruction = secret("claims", "tokenResponse.claims");
        instruction.setConvertToObject(true);
        templateData.put("tokenResponse", Map.of("claims", "{\"sub\":\"u\"}"));
        when(jsonSerialization.deserialize(anyString())).thenReturn(Map.of("sub", "u"));

        prePostUtils.executePropertyInstructions(List.of(instruction), 200, false, memory, templateData);

        verify(jsonSerialization).deserialize(anyString());
        verify(secretProvider, never()).store(any(), anyString(), anyString(), anyList());
        assertNull(memory.getConversationProperties().get("claims"));
    }
}
