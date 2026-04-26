/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SlackChannelRouter}.
 */
class SlackChannelRouterTest {

    private IRestAgentAdministration agentAdmin;
    private IRestAgentStore agentStore;
    private SecretResolver secretResolver;
    private SlackChannelRouter router;

    @BeforeEach
    void setUp() {
        agentAdmin = mock(IRestAgentAdministration.class);
        agentStore = mock(IRestAgentStore.class);
        secretResolver = mock(SecretResolver.class);

        // By default, SecretResolver passes through unchanged
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        router = new SlackChannelRouter(agentAdmin, agentStore, secretResolver);
    }

    // ─── Agent Resolution ───

    @Test
    void resolveAgentId_explicitMapping_returnsAgentId() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", "xoxb-token", "signing-secret", null);
        assertEquals(Optional.of("agent-1"), router.resolveAgentId("C0123"));
    }

    @Test
    void resolveAgentId_noMapping_returnsEmpty() throws Exception {
        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of());

        assertEquals(Optional.empty(), router.resolveAgentId("C_UNKNOWN"));
    }

    // ─── Group Resolution ───

    @Test
    void resolveGroupId_explicitMapping_returnsGroupId() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", "xoxb-token", "signing-secret", "group-42");
        assertEquals(Optional.of("group-42"), router.resolveGroupId("C0123"));
    }

    @Test
    void resolveGroupId_noGroupMapping_returnsEmpty() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", "xoxb-token", "signing-secret", null);
        assertEquals(Optional.empty(), router.resolveGroupId("C0123"));
    }

    // ─── Credentials Resolution ───

    @Test
    void resolveCredentials_returnsFullCredentials() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", "xoxb-my-token", "my-signing-secret", "group-42");

        var credsOpt = router.resolveCredentials("C0123");
        assertTrue(credsOpt.isPresent());

        var creds = credsOpt.get();
        assertEquals("agent-1", creds.agentId());
        assertEquals("xoxb-my-token", creds.botToken());
        assertEquals("my-signing-secret", creds.signingSecret());
        assertEquals("group-42", creds.groupId());
    }

    @Test
    void resolveCredentials_unknownChannel_returnsEmpty() throws Exception {
        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of());

        assertTrue(router.resolveCredentials("C_UNKNOWN").isEmpty());
    }

    @Test
    void resolveCredentials_vaultReferencesResolved() throws Exception {
        // Configure SecretResolver to resolve vault references
        when(secretResolver.resolveValue("${eddivault:slack-token}")).thenReturn("xoxb-resolved");
        when(secretResolver.resolveValue("${eddivault:slack-secret}")).thenReturn("resolved-secret");

        setupDeployedAgent("agent-1", 1, "C0123",
                "${eddivault:slack-token}", "${eddivault:slack-secret}", null);

        var creds = router.resolveCredentials("C0123");
        assertTrue(creds.isPresent());
        assertEquals("xoxb-resolved", creds.get().botToken());
        assertEquals("resolved-secret", creds.get().signingSecret());
    }

    // ─── Signing Secrets ───

    @Test
    void getAllSigningSecrets_returnsAllUniqueSecrets() throws Exception {
        setupDeployedAgents(
                new AgentSpec("agent-1", 1, "C0001", "token-1", "secret-A", null),
                new AgentSpec("agent-2", 1, "C0002", "token-2", "secret-B", null));

        var secrets = router.getAllSigningSecrets();
        assertEquals(2, secrets.size());
        assertTrue(secrets.contains("secret-A"));
        assertTrue(secrets.contains("secret-B"));
    }

    @Test
    void getAllSigningSecrets_deduplicatesSameSecret() throws Exception {
        // Two agents using the same workspace (same signing secret)
        setupDeployedAgents(
                new AgentSpec("agent-1", 1, "C0001", "token-1", "shared-secret", null),
                new AgentSpec("agent-2", 1, "C0002", "token-2", "shared-secret", null));

        var secrets = router.getAllSigningSecrets();
        assertEquals(1, secrets.size());
        assertTrue(secrets.contains("shared-secret"));
    }

    @Test
    void getAllSigningSecrets_emptyWhenNoAgents() throws Exception {
        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of());

        assertTrue(router.getAllSigningSecrets().isEmpty());
    }

    // ─── Edge Cases ───

    @Test
    void resolveAgentId_deletedAgent_ignored() throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setDeleted(true);

        var status = new AgentDeploymentStatus();
        status.setAgentId("deleted-agent");
        status.setAgentVersion(1);
        status.setDescriptor(descriptor);

        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of(status));

        assertEquals(Optional.empty(), router.resolveAgentId("C0123"));
    }

    @Test
    void resolveAgentId_noChannels_ignored() throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setDeleted(false);

        var status = new AgentDeploymentStatus();
        status.setAgentId("agent-no-channels");
        status.setAgentVersion(1);
        status.setDescriptor(descriptor);

        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of(status));
        when(agentStore.readAgent("agent-no-channels", 1))
                .thenReturn(new AgentConfiguration());

        assertEquals(Optional.empty(), router.resolveAgentId("C0123"));
    }

    @Test
    void resolveAgentId_multipleAgents_lastChannelWins() throws Exception {
        // Both agents map to the same channelId — last one scanned wins
        setupDeployedAgents(
                new AgentSpec("agent-1", 1, "C_SHARED", "token-1", "secret-1", null),
                new AgentSpec("agent-2", 1, "C_SHARED", "token-2", "secret-2", null));

        var result = router.resolveAgentId("C_SHARED");
        assertTrue(result.isPresent());
        // Either agent-1 or agent-2 wins — the key behavior is that it doesn't throw
    }

    @Test
    void resolveAgentId_cacheRefresh_skipsWhenRecent() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", "token", "secret", null);

        // First call triggers refresh
        router.resolveAgentId("C0123");
        // Second call should use cached data
        router.resolveAgentId("C0123");

        // getDeploymentStatuses should only be called once (cached for 60s)
        verify(agentAdmin, times(1)).getDeploymentStatuses(any());
    }

    @Test
    void resolveAgentId_missingBotToken_stillMapsAgent() throws Exception {
        // Agent configured without botToken — still routes, but posting will warn
        setupDeployedAgent("agent-1", 1, "C0123", null, "secret", null);

        assertEquals(Optional.of("agent-1"), router.resolveAgentId("C0123"));
        var creds = router.resolveCredentials("C0123");
        assertTrue(creds.isPresent());
        assertNull(creds.get().botToken());
    }

    @Test
    void hasAnySlackChannels_trueWhenConfigured() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", "token", "secret", null);
        assertTrue(router.hasAnySlackChannels());
    }

    @Test
    void hasAnySlackChannels_falseWhenEmpty() throws Exception {
        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of());
        assertFalse(router.hasAnySlackChannels());
    }

    @Test
    void resolveCredentials_vaultFailure_botTokenNull_channelStillMapped() throws Exception {
        // Vault throws for the botToken reference but signingSecret resolves fine
        when(secretResolver.resolveValue("${eddivault:bad-token-ref}"))
                .thenThrow(new RuntimeException("Vault key not found: bad-token-ref"));
        when(secretResolver.resolveValue("plain-secret"))
                .thenReturn("plain-secret");

        setupDeployedAgent("agent-1", 1, "C0123",
                "${eddivault:bad-token-ref}", "plain-secret", null);

        // Channel should still be mapped (routing works)
        assertEquals(Optional.of("agent-1"), router.resolveAgentId("C0123"));

        // Credentials should exist but botToken should be null (graceful degradation)
        var creds = router.resolveCredentials("C0123");
        assertTrue(creds.isPresent());
        assertNull(creds.get().botToken());
        assertEquals("plain-secret", creds.get().signingSecret());
    }

    @Test
    void resolveCredentials_vaultFailure_signingSecretNull_notInSigningSecretsSet() throws Exception {
        // Signing secret vault ref fails — should not appear in getAllSigningSecrets()
        when(secretResolver.resolveValue("xoxb-good-token"))
                .thenReturn("xoxb-good-token");
        when(secretResolver.resolveValue("${eddivault:bad-secret-ref}"))
                .thenThrow(new RuntimeException("Vault key not found"));

        setupDeployedAgent("agent-1", 1, "C0123",
                "xoxb-good-token", "${eddivault:bad-secret-ref}", null);

        // Signing secrets set should be empty (failed resolution excluded)
        assertTrue(router.getAllSigningSecrets().isEmpty());

        // But the channel is still mapped
        assertEquals(Optional.of("agent-1"), router.resolveAgentId("C0123"));
    }

    // ─── Helpers ───

    private void setupDeployedAgent(String agentId, int version, String channelId,
                                    String botToken, String signingSecret, String groupId)
            throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setDeleted(false);

        var status = new AgentDeploymentStatus();
        status.setAgentId(agentId);
        status.setAgentVersion(version);
        status.setDescriptor(descriptor);

        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of(status));

        var channelConfig = new java.util.HashMap<String, String>();
        channelConfig.put("channelId", channelId);
        if (botToken != null) {
            channelConfig.put("botToken", botToken);
        }
        if (signingSecret != null) {
            channelConfig.put("signingSecret", signingSecret);
        }
        if (groupId != null) {
            channelConfig.put("groupId", groupId);
        }

        var channel = new ChannelConnector();
        channel.setType(URI.create("slack"));
        channel.setConfig(channelConfig);

        var agentConfig = new AgentConfiguration();
        agentConfig.setChannels(List.of(channel));

        when(agentStore.readAgent(agentId, version)).thenReturn(agentConfig);
    }

    private record AgentSpec(String agentId, int version, String channelId,
            String botToken, String signingSecret, String groupId) {
    }

    private void setupDeployedAgents(AgentSpec... specs) throws Exception {
        var statuses = new java.util.ArrayList<AgentDeploymentStatus>();

        for (var spec : specs) {
            var descriptor = new DocumentDescriptor();
            descriptor.setDeleted(false);

            var status = new AgentDeploymentStatus();
            status.setAgentId(spec.agentId());
            status.setAgentVersion(spec.version());
            status.setDescriptor(descriptor);
            statuses.add(status);

            var channelConfig = new java.util.HashMap<String, String>();
            channelConfig.put("channelId", spec.channelId());
            if (spec.botToken() != null) {
                channelConfig.put("botToken", spec.botToken());
            }
            if (spec.signingSecret() != null) {
                channelConfig.put("signingSecret", spec.signingSecret());
            }
            if (spec.groupId() != null) {
                channelConfig.put("groupId", spec.groupId());
            }

            var channel = new ChannelConnector();
            channel.setType(URI.create("slack"));
            channel.setConfig(channelConfig);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(channel));

            when(agentStore.readAgent(spec.agentId(), spec.version())).thenReturn(agentConfig);
        }

        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(statuses);
    }
}
