/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.AutoVaultReference;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C2 — a {@code scope: "secret"} property must be vaulted per conversation.
 * <p>
 * The key used to be {@code <agentId>.<property>} under a tenant read from a
 * client-settable {@code tenantId} property. The vault write is an upsert, so
 * two users of one agent shared a single entry, and whoever wrote last supplied
 * the credential for everyone's calls; the resolver cache was never invalidated
 * on that path either.
 */
class SecretPropertyVaultTest {

    private ISecretProvider secretProvider;
    private SecretResolver secretResolver;
    private SecretPropertyVault vault;

    @BeforeEach
    void setUp() {
        secretProvider = mock(ISecretProvider.class);
        secretResolver = mock(SecretResolver.class);
        vault = new SecretPropertyVault(secretProvider, secretResolver, new DataFactory());
    }

    private static ConversationMemory conversation(String conversationId, String userId) {
        var memory = new ConversationMemory(conversationId, "agent-1", 1, userId);
        memory.getCurrentStep().storeData(new Data<>("input:initial", "irrelevant"));
        return memory;
    }

    @Test
    @DisplayName("two conversations of the same agent get two vault entries — one user never overwrites another's secret")
    void keyedPerConversation() throws Exception {
        Property alice = vault.vault(conversation("aaaaaaaaaaaaaaaaaaaaaaaa", "alice"), "apiKey", "alice-secret-value");
        Property bob = vault.vault(conversation("bbbbbbbbbbbbbbbbbbbbbbbb", "bob"), "apiKey", "bob-secret-value");

        assertEquals("${vault:agent-1.aaaaaaaaaaaaaaaaaaaaaaaa.apiKey}", alice.getValueString());
        assertEquals("${vault:agent-1.bbbbbbbbbbbbbbbbbbbbbbbb.apiKey}", bob.getValueString());
        assertNotEquals(alice.getValueString(), bob.getValueString());

        var refs = ArgumentCaptor.forClass(SecretReference.class);
        verify(secretProvider, times(2)).store(refs.capture(), anyString(), anyString(), anyList());
        assertEquals(new SecretReference("default", "agent-1.aaaaaaaaaaaaaaaaaaaaaaaa.apiKey"), refs.getAllValues().get(0));
        assertEquals(new SecretReference("default", "agent-1.bbbbbbbbbbbbbbbbbbbbbbbb.apiKey"), refs.getAllValues().get(1));
    }

    @Test
    @DisplayName("the resolver cache is invalidated after the write, so a re-entered secret is not served stale")
    void invalidatesTheResolverCache() throws Exception {
        vault.vault(conversation("aaaaaaaaaaaaaaaaaaaaaaaa", "alice"), "apiKey", "first-value");

        var ref = new SecretReference("default", "agent-1.aaaaaaaaaaaaaaaaaaaaaaaa.apiKey");
        var order = inOrder(secretProvider, secretResolver);
        order.verify(secretProvider).store(eq(ref), eq("first-value"), anyString(), eq(List.of("agent-1")));
        order.verify(secretResolver).invalidateCache(ref);
    }

    @Test
    @DisplayName("a client-settable tenantId property does not choose the tenant")
    void tenantPropertyIsIgnored() throws Exception {
        var memory = conversation("aaaaaaaaaaaaaaaaaaaaaaaa", "alice");
        memory.getConversationProperties().put("tenantId", new Property("tenantId", "victim-tenant", Scope.conversation));

        Property stored = vault.vault(memory, "apiKey", "value");

        assertEquals("${vault:agent-1.aaaaaaaaaaaaaaaaaaaaaaaa.apiKey}", stored.getValueString());
        var ref = ArgumentCaptor.forClass(SecretReference.class);
        verify(secretProvider).store(ref.capture(), anyString(), anyString(), anyList());
        assertEquals(SecretReference.DEFAULT_TENANT, ref.getValue().tenantId());
    }

    @Test
    @DisplayName("the stored property is conversation-scoped and carries the provenance marker")
    void storedPropertyShape() throws Exception {
        Property stored = vault.vault(conversation("aaaaaaaaaaaaaaaaaaaaaaaa", "alice"), "apiKey", "value");

        assertEquals(Scope.conversation, stored.getScope());
        assertEquals(Boolean.TRUE, stored.getAutoVaulted());
        assertEquals(AutoVaultReference.of("agent-1", "aaaaaaaaaaaaaaaaaaaaaaaa", "apiKey").toReferenceString(),
                stored.getValueString(), "the guard and the writer derive the key from one definition");
    }

    @Test
    @DisplayName("a non-string value is refused and never stored")
    void nonStringRefused() throws Exception {
        var memory = conversation("aaaaaaaaaaaaaaaaaaaaaaaa", "alice");

        var e = assertThrows(LifecycleException.class, () -> vault.vault(memory, "apiKey", Map.of("token", "x")));

        assertTrue(e.getMessage().contains("only string values can be vaulted"), e.getMessage());
        verify(secretProvider, never()).store(any(), anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("a name that would change what the reference parses to is refused, after the input is scrubbed")
    void unembeddableNameRefused() throws Exception {
        var memory = conversation("aaaaaaaaaaaaaaaaaaaaaaaa", "alice");
        memory.getCurrentStep().storeData(new Data<>("input:initial", "typed-secret-value"));

        assertThrows(LifecycleException.class, () -> vault.vault(memory, "other-tenant/apiKey", "typed-secret-value"));

        verify(secretProvider, never()).store(any(), anyString(), anyString(), anyList());
        assertFalse(String.valueOf(memory.getCurrentStep().getLatestData("input:initial").getResult()).contains("typed-secret-value"));
    }

    @Test
    @DisplayName("AutoVaultReference refuses missing ids and unembeddable parts")
    void referenceForGuards() {
        assertNull(AutoVaultReference.of(null, "c", "p"));
        assertNull(AutoVaultReference.of("a", "", "p"));
        assertNull(AutoVaultReference.of("a", "c", "p}x"));
        assertEquals(new SecretReference("default", "a.c.p"), AutoVaultReference.of("a", "c", "p"));
    }

    @Test
    @DisplayName("review #3: deleting conversations removes exactly their auto-vaulted entries")
    void deleteConversationSecrets() throws Exception {
        when(secretProvider.isAvailable()).thenReturn(true);
        when(secretProvider.listKeys("default")).thenReturn(List.of(
                entry("agent-1.conv1.apiKey", "Auto-vaulted from conversation conv1"),
                entry("agent-1.conv1.token", "Auto-vaulted from conversation conv1"),
                entry("agent-1.conv2.apiKey", "Auto-vaulted from conversation conv2"),
                // an operator's secret that merely looks like one: different description
                entry("team.conv1.shared", "created by ops"),
                // described as conv1's but keyed elsewhere: never touched
                entry("openai-prod", "Auto-vaulted from conversation conv1")));

        int deleted = vault.deleteConversationSecrets(List.of("conv1"));

        assertEquals(2, deleted);
        verify(secretProvider).delete(new SecretReference("default", "agent-1.conv1.apiKey"));
        verify(secretProvider).delete(new SecretReference("default", "agent-1.conv1.token"));
        verify(secretProvider, times(2)).delete(any());
        verify(secretResolver).invalidateCache(new SecretReference("default", "agent-1.conv1.apiKey"));
    }

    @Test
    @DisplayName("review #3: with the vault disabled nothing is listed or deleted")
    void deleteConversationSecretsVaultDisabled() throws Exception {
        assertEquals(0, vault.deleteConversationSecrets(List.of("conv1")));
        verify(secretProvider, never()).listKeys(anyString());
    }

    private static SecretMetadata entry(String keyName, String description) {
        return new SecretMetadata("default", keyName, Instant.now(), null, null, "x", description, List.of("agent-1"));
    }
}
