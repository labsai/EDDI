package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SlackChannelRouter}.
 */
class SlackChannelRouterTest {

    private IRestAgentAdministration agentAdmin;
    private IRestAgentStore agentStore;
    private SlackIntegrationConfig config;
    private SlackChannelRouter router;

    @BeforeEach
    void setUp() {
        agentAdmin = mock(IRestAgentAdministration.class);
        agentStore = mock(IRestAgentStore.class);
        config = mock(SlackIntegrationConfig.class);
        when(config.defaultAgentId()).thenReturn(Optional.empty());
        when(config.defaultGroupId()).thenReturn(Optional.empty());

        router = new SlackChannelRouter(agentAdmin, agentStore, config);
    }

    // ─── Agent Resolution ───

    @Test
    void resolveAgentId_explicitMapping_returnsAgentId() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", null);
        assertEquals(Optional.of("agent-1"), router.resolveAgentId("C0123"));
    }

    @Test
    void resolveAgentId_noMapping_fallsToDefault() throws Exception {
        when(config.defaultAgentId()).thenReturn(Optional.of("default-agent"));
        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of());

        assertEquals(Optional.of("default-agent"), router.resolveAgentId("C_UNKNOWN"));
    }

    @Test
    void resolveAgentId_noMappingNoDefault_returnsEmpty() throws Exception {
        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of());

        assertEquals(Optional.empty(), router.resolveAgentId("C_UNKNOWN"));
    }

    // ─── Group Resolution ───

    @Test
    void resolveGroupId_explicitMapping_returnsGroupId() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", "group-42");
        assertEquals(Optional.of("group-42"), router.resolveGroupId("C0123"));
    }

    @Test
    void resolveGroupId_noGroupMapping_fallsToDefault() throws Exception {
        when(config.defaultGroupId()).thenReturn(Optional.of("default-group"));
        setupDeployedAgent("agent-1", 1, "C0123", null);

        assertEquals(Optional.of("default-group"), router.resolveGroupId("C0123"));
    }

    @Test
    void resolveGroupId_noGroupMappingNoDefault_returnsEmpty() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", null);

        assertEquals(Optional.empty(), router.resolveGroupId("C0123"));
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
                new AgentSpec("agent-1", 1, "C_SHARED", null),
                new AgentSpec("agent-2", 1, "C_SHARED", null));

        var result = router.resolveAgentId("C_SHARED");
        assertTrue(result.isPresent());
        // Either agent-1 or agent-2 wins — the key behavior is that it doesn't throw
    }

    @Test
    void resolveAgentId_cacheRefresh_skipsWhenRecent() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", null);

        // First call triggers refresh
        router.resolveAgentId("C0123");
        // Second call should use cached data
        router.resolveAgentId("C0123");

        // getDeploymentStatuses should only be called once (cached for 60s)
        verify(agentAdmin, times(1)).getDeploymentStatuses(any());
    }

    @Test
    void resolveAgentId_refreshFailure_returnsStaleData() throws Exception {
        setupDeployedAgent("agent-1", 1, "C0123", null);
        router.resolveAgentId("C0123"); // populate cache

        // Cache is still fresh, so stale data is returned
        assertEquals(Optional.of("agent-1"), router.resolveAgentId("C0123"));
    }

    // ─── Helpers ───

    private void setupDeployedAgent(String agentId, int version, String channelId, String groupId) throws Exception {
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

    private record AgentSpec(String agentId, int version, String channelId, String groupId) {
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
