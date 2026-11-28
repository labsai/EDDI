/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.security.spaces.SpaceTenants;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.secrets.AutoVaultedSecrets;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The vaulting every {@code scope: "secret"} property path shares — the
 * property setter and the httpcall / MCP / LLM property instructions. The slot
 * naming itself is {@code AutoVaultedSecrets}' and tested there.
 */
class SecretPropertyVaultTest {

    private ISecretProvider secretProvider;
    private SecretPropertyVault vault;

    @BeforeEach
    void setUp() {
        secretProvider = mock(ISecretProvider.class);
        vault = new SecretPropertyVault(secretProvider, new DataFactory());
    }

    private static ConversationMemory conversation(String userId) {
        var memory = new ConversationMemory("aaaaaaaaaaaaaaaaaaaaaaaa", "agent-1", 1, userId);
        memory.getCurrentStep().storeData(new Data<>("input:initial", "irrelevant"));
        return memory;
    }

    @Test
    @DisplayName("the stored property is conversation-scoped, carries the provenance marker and names this user's own slot")
    void storedPropertyShape() throws Exception {
        Property stored = vault.vault(conversation("alice"), "apiKey", "value");

        assertEquals(Scope.conversation, stored.getScope());
        assertEquals(Boolean.TRUE, stored.getAutoVaulted());
        var ref = ArgumentCaptor.forClass(SecretReference.class);
        verify(secretProvider).store(ref.capture(), anyString(), anyString(), anyList());
        assertEquals(ref.getValue().toReferenceString(), stored.getValueString());
        assertTrue(AutoVaultedSecrets.isSlotFor(ref.getValue().keyName(), "agent-1", "alice", "apiKey"), ref.getValue().keyName());
    }

    @Test
    @DisplayName("a non-string value is refused and never stored")
    void nonStringRefused() throws Exception {
        var memory = conversation("alice");

        var e = assertThrows(LifecycleException.class, () -> vault.vault(memory, "apiKey", Map.of("token", "x")));

        assertTrue(e.getMessage().contains("only string values can be vaulted"), e.getMessage());
        verify(secretProvider, never()).store(any(), anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("a name that would change what the reference parses to is refused, after the input is scrubbed")
    void unembeddableNameRefused() throws Exception {
        var memory = conversation("alice");
        memory.getCurrentStep().storeData(new Data<>("input:initial", "typed-secret-value"));

        assertThrows(LifecycleException.class, () -> vault.vault(memory, "other-tenant/apiKey", "typed-secret-value"));
        assertThrows(LifecycleException.class, () -> vault.vault(memory, "api}Key", "typed-secret-value"));

        verify(secretProvider, never()).store(any(), anyString(), anyString(), anyList());
        assertFalse(String.valueOf(memory.getCurrentStep().getLatestData("input:initial").getResult()).contains("typed-secret-value"));
    }

    @Test
    @DisplayName("a disabled vault fails closed, after the input is scrubbed")
    void vaultFailureFailsClosed() throws Exception {
        var memory = conversation("alice");
        memory.getCurrentStep().storeData(new Data<>("input:initial", "typed-secret-value"));
        doThrow(new ISecretProvider.SecretProviderException("vault disabled")).when(secretProvider).store(any(), anyString(), anyString(),
                anyList());

        var e = assertThrows(LifecycleException.class, () -> vault.vault(memory, "apiKey", "typed-secret-value"));

        assertTrue(e.getMessage().contains("EDDI_VAULT_MASTER_KEY"), e.getMessage());
        assertFalse(String.valueOf(memory.getCurrentStep().getLatestData("input:initial").getResult()).contains("typed-secret-value"));
    }

    @Test
    @DisplayName("AutoVaultedSecrets.isEmbeddable refuses a separator, braces, $ and control characters")
    void embeddableNames() {
        assertTrue(AutoVaultedSecrets.isEmbeddable("apiKey"));
        assertTrue(AutoVaultedSecrets.isEmbeddable("api key"));
        assertFalse(AutoVaultedSecrets.isEmbeddable(null));
        assertFalse(AutoVaultedSecrets.isEmbeddable(""));
        assertFalse(AutoVaultedSecrets.isEmbeddable("a/b"));
        assertFalse(AutoVaultedSecrets.isEmbeddable("a}b"));
        assertFalse(AutoVaultedSecrets.isEmbeddable("a{b"));
        assertFalse(AutoVaultedSecrets.isEmbeddable("a$b"));
        assertFalse(AutoVaultedSecrets.isEmbeddable("a\nb"));
    }

    @Nested
    @DisplayName("the vault tenant (security review 2026-11, finding 4)")
    class VaultTenant {

        private static final String OWN_SPACE = "team:research";
        private static final String FOREIGN_SPACE = "team:finance";

        private WorkspaceSettings workspaceSettings;

        @BeforeEach
        void inASpace() throws Exception {
            workspaceSettings = mock(WorkspaceSettings.class);
            when(workspaceSettings.isEnforcing()).thenReturn(true);
            var descriptor = new DocumentDescriptor();
            descriptor.setSpaceId(OWN_SPACE);
            IDocumentDescriptorStore descriptorStore = mock(IDocumentDescriptorStore.class);
            when(descriptorStore.readCurrentDescriptor("agent-1")).thenReturn(descriptor);
            vault.workspaceSettings = workspaceSettings;
            vault.descriptorStore = descriptorStore;
        }

        private ConversationMemory askingForTenant(String tenantId) {
            var memory = conversation("alice");
            memory.getConversationProperties().put("tenantId", new Property("tenantId", tenantId, Scope.conversation));
            return memory;
        }

        private SecretReference stored() throws Exception {
            var ref = ArgumentCaptor.forClass(SecretReference.class);
            verify(secretProvider).store(ref.capture(), anyString(), anyString(), anyList());
            return ref.getValue();
        }

        @Test
        @DisplayName("the exploit: a tenantId property naming another space's tenant is ignored")
        void foreignSpaceTenantIsIgnored() throws Exception {
            vault.vault(askingForTenant(SpaceTenants.tenantFor(FOREIGN_SPACE)), "apiKey", "value");

            assertEquals(SecretReference.DEFAULT_TENANT, stored().tenantId());
        }

        @Test
        @DisplayName("an administrator-made tenant is not reachable through the property either")
        void otherTenantIsIgnored() throws Exception {
            vault.vault(askingForTenant("acme"), "apiKey", "value");

            assertEquals(SecretReference.DEFAULT_TENANT, stored().tenantId());
        }

        @Test
        @DisplayName("the agent's own space's tenant is honoured")
        void ownSpaceTenantIsHonoured() throws Exception {
            String own = SpaceTenants.tenantFor(OWN_SPACE);

            Property property = vault.vault(askingForTenant(own), "apiKey", "value");

            assertEquals(own, stored().tenantId());
            assertTrue(property.getValueString().startsWith("${vault:" + own + "/"), property.getValueString());
        }

        @Test
        @DisplayName("without workspace enforcement the property is honoured as before, and without it the tenant is default")
        void unchangedWithoutEnforcement() throws Exception {
            when(workspaceSettings.isEnforcing()).thenReturn(false);
            vault.vault(askingForTenant("acme"), "apiKey", "value");
            assertEquals("acme", stored().tenantId());
        }

        @Test
        @DisplayName("no property, default tenant")
        void defaultWithoutProperty() throws Exception {
            vault.vault(conversation("alice"), "apiKey", "value");

            assertEquals(SecretReference.DEFAULT_TENANT, stored().tenantId());
        }
    }

    @Test
    @DisplayName("a credential inside a typed output item of the step is scrubbed too")
    void typedOutputItemIsScrubbed() throws Exception {
        var memory = conversation("alice");
        String secret = "plain-test-credential-value";
        memory.getCurrentStep().addConversationOutputObject("output", new TextOutputItem("your key is " + secret));

        vault.vault(memory, "apiKey", secret);

        String stored = String.valueOf(memory.getCurrentStep().getConversationOutput().get("output"));
        assertFalse(stored.contains(secret), stored);
    }
}
