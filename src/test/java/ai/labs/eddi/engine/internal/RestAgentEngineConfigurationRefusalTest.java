/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.lifecycle.exceptions.AgentConfigurationRefusal;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A conversation start the agent's own configuration refuses answers 422 with a
 * displayable reason; every other failure of the start turn stays a 500.
 */
class RestAgentEngineConfigurationRefusalTest {

    private IConversationService conversationService;
    private RestAgentEngine restAgentEngine;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        var descriptorStore = mock(IConversationDescriptorStore.class);
        var identity = mock(SecurityIdentity.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        var conversationAccessGuard = new ConversationAccessGuard(identity, ownershipValidator, descriptorStore);
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore, conversationService,
                mock(IGroupConversationService.class));
        restAgentEngine = new RestAgentEngine(conversationService, mock(IConversationMemoryStore.class), identity, ownershipValidator,
                conversationAccessGuard, mock(ResourceAccessGuard.class), hitlAccessGuard, mock(IHitlToolJournalStore.class),
                mock(AgentDisplayNameResolver.class), 30);
    }

    /**
     * The chain ConversationService builds around a task failure on the start turn.
     */
    private static ResourceStoreException startTurnFailure(Exception taskFailure) {
        var task = new LifecycleException(taskFailure.getMessage(), taskFailure);
        var pipeline = new LifecycleException("Error while executing lifecycle!", task);
        return new ResourceStoreException(pipeline.getLocalizedMessage(), pipeline);
    }

    private Response start() {
        return restAgentEngine.startConversationWithContext("agent-1", Environment.production, "user-1", Map.of());
    }

    @Test
    @DisplayName("an httpcall vault reference naming no secret: 422 agent_misconfigured, naming the call and the tenant")
    void unresolvedVaultReferenceIs422() throws Exception {
        var refusal = SecretResolver.unresolvedReferenceFailure("header 'X-Api-Key'", "${vault:api-key}",
                SecretResolver.UnresolvedReason.NOT_FOUND);
        var named = new SecretResolver.MisconfiguredVaultReferenceException("API call 'weather': " + refusal.getMessage(), refusal);
        when(conversationService.startConversation(any(), anyString(), any(), any())).thenThrow(startTurnFailure(named));

        Response response = start();

        assertEquals(422, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getEntity();
        assertEquals(RestAgentEngine.AGENT_MISCONFIGURED, body.get("error"));
        assertEquals(false, body.get("retryable"));
        String message = (String) body.get("message");
        assertTrue(message.startsWith("API call 'weather': header 'X-Api-Key' references ${vault:api-key}"), message);
        assertTrue(message.contains("tenant 'default'"), message);
        assertFalse(message.contains("Error while executing lifecycle"), message);
        assertFalse(message.contains("\tat "), message);
    }

    @Test
    @DisplayName("a secret sent to a host its allowedHosts does not name: 422")
    void hostRefusalIs422() throws Exception {
        var refusal = new VaultHostAllowlist.HostRefusedException("API call 'weather' sends ${vault:api-key} to evil.example:443, which is not "
                + "on that secret's allowedHosts [api.example]");
        when(conversationService.startConversation(any(), anyString(), any(), any())).thenThrow(startTurnFailure(refusal));

        Response response = start();

        assertEquals(422, response.getStatus());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getEntity();
        assertTrue(((String) body.get("message")).contains("allowedHosts [api.example]"));
    }

    @Test
    @DisplayName("a refusal thrown unwrapped as a RuntimeException is a 422 too")
    void uncheckedRefusalIs422() throws Exception {
        when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenThrow(new VaultHostAllowlist.HostRefusedException("refused"));

        assertEquals(422, start().getStatus());
    }

    @Test
    @DisplayName("a vault that could not be read is NOT a misconfiguration: still a 500")
    void vaultOutageStays500() throws Exception {
        var outage = SecretResolver.unresolvedReferenceFailure("header 'X-Api-Key'", "${vault:api-key}",
                SecretResolver.UnresolvedReason.PROVIDER_FAILED);
        assertNull(AgentConfigurationRefusal.find(outage));
        when(conversationService.startConversation(any(), anyString(), any(), any())).thenThrow(startTurnFailure(outage));

        assertThrows(InternalServerErrorException.class, this::start);
    }

    @Test
    @DisplayName("the allowed hosts could not be read: still a 500")
    void unreadableAllowListStays500() throws Exception {
        var unreadable = new VaultHostAllowlist.HostNotAllowedException("The allowed hosts of ${vault:api-key} could not be read");
        when(conversationService.startConversation(any(), anyString(), any(), any())).thenThrow(startTurnFailure(unreadable));

        assertThrows(InternalServerErrorException.class, this::start);
    }

    @Test
    @DisplayName("any other task failure (a plain IllegalArgumentException) stays a 500")
    void otherFailureStays500() throws Exception {
        when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenThrow(startTurnFailure(new IllegalArgumentException("Cannot resolve hostname: api.example")));

        assertThrows(InternalServerErrorException.class, this::start);
    }

    @Test
    @DisplayName("an unchecked failure that is not a refusal is rethrown unchanged")
    void otherUncheckedFailureIsRethrown() throws Exception {
        var failure = new IllegalStateException("boom");
        when(conversationService.startConversation(any(), anyString(), any(), any())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, this::start));
    }

    @Test
    @DisplayName("the message is redacted and bounded")
    void messageIsRedactedAndBounded() {
        var refusal = new VaultHostAllowlist.HostRefusedException("Authorization: Bearer sk-live-abcdefghijklmnopqrstuvwxyz0123456789 "
                + "x".repeat(5000));

        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) RestAgentEngine.configurationRefused("agent-1", refusal).getEntity();
        String message = (String) body.get("message");

        assertFalse(message.contains("sk-live-abcdefghijklmnopqrstuvwxyz0123456789"), message);
        assertTrue(message.length() <= RestAgentEngine.MAX_REFUSAL_MESSAGE_CHARS + 3, String.valueOf(message.length()));
    }

    @Test
    @DisplayName("find walks the cause chain, stops on a cycle and on depth")
    void findIsBoundedAndCycleSafe() {
        var refusal = new VaultHostAllowlist.HostRefusedException("refused");
        assertSame(refusal, AgentConfigurationRefusal.find(new RuntimeException(new RuntimeException(refusal))));
        assertNull(AgentConfigurationRefusal.find(null));

        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);
        assertNull(AgentConfigurationRefusal.find(a));

        Throwable deep = refusal;
        for (int i = 0; i < AgentConfigurationRefusal.MAX_CAUSE_DEPTH; i++) {
            deep = new RuntimeException(deep);
        }
        assertNull(AgentConfigurationRefusal.find(deep));
    }
}
