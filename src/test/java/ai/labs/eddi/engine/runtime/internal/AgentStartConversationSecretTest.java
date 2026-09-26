/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Review finding 1 — a {@code scope: "secret"} property set on the
 * CONVERSATION_START turn must vault. The vault key contains the conversation
 * id, and a new conversation used to receive its id only when it was first
 * stored, after that turn — so the turn failed and the conversation could not
 * be started. The id is now allocated before {@code init()}.
 */
class AgentStartConversationSecretTest {

    private static final String PREALLOCATED_ID = "aabbccddeeff112233445566";

    @Test
    @DisplayName("the CONVERSATION_START turn already carries the pre-allocated id, so a secret set there vaults")
    void initTurnCanVaultASecret() throws Exception {
        ISecretProvider secretProvider = mock(ISecretProvider.class);
        var vault = new SecretPropertyVault(secretProvider, mock(SecretResolver.class), new DataFactory());

        ILifecycleManager lifecycleManager = mock(ILifecycleManager.class);
        IExecutableWorkflow workflow = mock(IExecutableWorkflow.class);
        lenient().when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        lenient().when(workflow.getWorkflowId()).thenReturn("wf1");

        AtomicReference<Property> vaulted = new AtomicReference<>();
        doAnswer(invocation -> {
            IConversationMemory memory = invocation.getArgument(0);
            // What a property setter on CONVERSATION_START does with {context.apiKey}.
            vaulted.set(vault.vault(memory, "apiKey", "client-supplied-secret-value"));
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());

        var agent = new Agent("agent1", 1);
        agent.addWorkflow(workflow);
        IConversation conversation = agent.startConversation(PREALLOCATED_ID, "user1",
                Map.of("apiKey", new Context(Context.ContextType.string, "client-supplied-secret-value")), mock(IPropertiesHandler.class),
                null);

        IConversationMemory memory = conversation.getConversationMemory();
        assertEquals(PREALLOCATED_ID, memory.getConversationId());
        assertTrue(memory.isUnpersisted(), "the first store must insert under the pre-allocated id");
        assertEquals("${vault:agent1." + PREALLOCATED_ID + ".apiKey}", vaulted.get().getValueString());
        verify(secretProvider).store(eq(new SecretReference("default", "agent1." + PREALLOCATED_ID + ".apiKey")),
                eq("client-supplied-secret-value"), anyString(), anyList());
    }

    @Test
    @DisplayName("without a pre-allocated id the memory is a plain new conversation, as before")
    void legacyOverloadStillWorks() throws Exception {
        IExecutableWorkflow workflow = mock(IExecutableWorkflow.class);
        lenient().when(workflow.getLifecycleManager()).thenReturn(mock(ILifecycleManager.class));
        var agent = new Agent("agent1", 1);
        agent.addWorkflow(workflow);

        IConversation conversation = agent.startConversation("user1", Map.of(), mock(IPropertiesHandler.class), null);

        assertFalse(conversation.getConversationMemory().isUnpersisted());
        assertNull(conversation.getConversationMemory().getConversationId());
    }
}
