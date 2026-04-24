package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChannelConnectorMigration}. Tests migration from legacy
 * embedded ChannelConnectors to standalone ChannelIntegrationConfiguration.
 */
class ChannelConnectorMigrationTest {

    private IDeploymentStore deploymentStore;
    private IAgentStore agentStore;
    private IChannelIntegrationStore channelStore;
    private IDocumentDescriptorStore descriptorStore;
    private MigrationLogStore migrationLogStore;
    private ChannelConnectorMigration migration;

    @BeforeEach
    void setUp() {
        deploymentStore = mock(IDeploymentStore.class);
        agentStore = mock(IAgentStore.class);
        channelStore = mock(IChannelIntegrationStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        migrationLogStore = mock(MigrationLogStore.class);

        migration = new ChannelConnectorMigration(
                deploymentStore, agentStore, channelStore, descriptorStore, migrationLogStore);
    }

    // ─── Skip if already migrated ─────────────────────────────────────────────

    @Nested
    @DisplayName("Migration skip logic")
    class SkipLogic {

        @Test
        @DisplayName("skips if migration flag already set")
        void skipIfAlreadyMigrated() {
            when(migrationLogStore.readMigrationLog("channel-connector-migration-complete"))
                    .thenReturn(new MigrationLog("channel-connector-migration-complete"));

            migration.runIfNeeded();

            verifyNoInteractions(deploymentStore, agentStore, channelStore);
        }

        @Test
        @DisplayName("runs if migration flag not set")
        void runsIfNotMigrated() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);
            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of());

            migration.runIfNeeded();

            verify(deploymentStore).readDeploymentInfos(deployed);
            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }
    }

    // ─── Basic migration ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Basic migration")
    class BasicMigration {

        @Test
        @DisplayName("migrates single agent with single channel connector")
        void singleAgentSingleChannel() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);
            setupDeployedAgent("agent-1", 1, "slack", "C001", "xoxb-tok", "sign-sec", null);

            var descriptor = new DocumentDescriptor();
            descriptor.setName("Test Agent");
            when(descriptorStore.readDescriptor("agent-1", 1)).thenReturn(descriptor);

            migration.runIfNeeded();

            var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
            verify(channelStore).create(captor.capture());
            var config = captor.getValue();

            assertEquals("slack", config.getChannelType());
            assertEquals("slack — C001", config.getName());
            assertEquals(1, config.getTargets().size());
            assertEquals("test-agent", config.getTargets().get(0).getName());
            assertEquals(ChannelTarget.TargetType.AGENT, config.getTargets().get(0).getType());
            assertEquals("agent-1", config.getTargets().get(0).getTargetId());
        }

        @Test
        @DisplayName("migrates agent with groupId → GROUP target type")
        void groupIdMigration() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);
            setupDeployedAgent("agent-1", 1, "slack", "C001", "tok", "sign", "group-xyz");

            when(descriptorStore.readDescriptor("agent-1", 1)).thenReturn(null);

            migration.runIfNeeded();

            var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
            verify(channelStore).create(captor.capture());
            var config = captor.getValue();

            assertEquals(ChannelTarget.TargetType.GROUP, config.getTargets().get(0).getType());
            assertEquals("group-xyz", config.getTargets().get(0).getTargetId());
        }

        @Test
        @DisplayName("platformConfig contains only channel-level credentials, not per-connector fields")
        void cleanedPlatformConfig() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);
            setupDeployedAgent("agent-1", 1, "slack", "C001", "tok", "sign", "group-x");

            when(descriptorStore.readDescriptor("agent-1", 1)).thenReturn(null);

            migration.runIfNeeded();

            var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
            verify(channelStore).create(captor.capture());
            var platformConfig = captor.getValue().getPlatformConfig();

            assertTrue(platformConfig.containsKey("channelId"));
            assertTrue(platformConfig.containsKey("botToken"));
            assertTrue(platformConfig.containsKey("signingSecret"));
            assertFalse(platformConfig.containsKey("groupId"),
                    "groupId is a per-connector field and should not leak into channel-level platformConfig");
        }
    }

    // ─── Multi-agent merging ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Multi-agent merging")
    class MultiAgentMerging {

        @Test
        @DisplayName("multiple agents on same channel → merged into one config with multiple targets")
        void mergesMultipleAgents() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            // Two agents on the same channel
            var connector1 = createConnector("slack", "C001", "tok", "sign", null);
            var connector2 = createConnector("slack", "C001", "tok", "sign", null);

            var agent1 = new AgentConfiguration();
            agent1.setChannels(List.of(connector1));
            var agent2 = new AgentConfiguration();
            agent2.setChannels(List.of(connector2));

            var status1 = createStatus("agent-aaa", 1);
            var status2 = createStatus("agent-bbb", 1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status1, status2));
            when(agentStore.read("agent-aaa", 1)).thenReturn(agent1);
            when(agentStore.read("agent-bbb", 1)).thenReturn(agent2);

            var desc1 = new DocumentDescriptor();
            desc1.setName("Alpha Agent");
            when(descriptorStore.readDescriptor("agent-aaa", 1)).thenReturn(desc1);

            var desc2 = new DocumentDescriptor();
            desc2.setName("Beta Agent");
            when(descriptorStore.readDescriptor("agent-bbb", 1)).thenReturn(desc2);

            migration.runIfNeeded();

            var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
            verify(channelStore, times(1)).create(captor.capture());
            var config = captor.getValue();

            assertEquals(2, config.getTargets().size());
            // Sorted by agentId: agent-aaa first
            assertEquals("alpha-agent", config.getTargets().get(0).getName());
            assertEquals("beta-agent", config.getTargets().get(1).getName());
        }
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("agent with null channels → skipped")
        void nullChannels() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(null);

            var status = createStatus("agent-1", 1);
            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status));
            when(agentStore.read("agent-1", 1)).thenReturn(agentConfig);

            migration.runIfNeeded();

            verify(channelStore, never()).create(any());
            verify(migrationLogStore).createMigrationLog(any());
        }

        @Test
        @DisplayName("connector with null type → skipped")
        void nullConnectorType() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var connector = new ChannelConnector();
            connector.setType(null);
            connector.setConfig(Map.of("channelId", "C001"));

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));

            var status = createStatus("agent-1", 1);
            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status));
            when(agentStore.read("agent-1", 1)).thenReturn(agentConfig);

            migration.runIfNeeded();

            verify(channelStore, never()).create(any());
        }

        @Test
        @DisplayName("connector with blank channelId → skipped")
        void blankChannelId() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(Map.of("channelId", "  "));

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));

            var status = createStatus("agent-1", 1);
            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status));
            when(agentStore.read("agent-1", 1)).thenReturn(agentConfig);

            migration.runIfNeeded();

            verify(channelStore, never()).create(any());
        }

        @Test
        @DisplayName("deployment with null agentId → skipped")
        void nullAgentId() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var status = new DeploymentInfo();
            status.setDeploymentStatus(deployed);
            status.setAgentId(null);
            status.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status));

            migration.runIfNeeded();

            verify(agentStore, never()).read(any(), anyInt());
        }

        @Test
        @DisplayName("deployment with null agentVersion → skipped")
        void nullAgentVersion() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var status = new DeploymentInfo();
            status.setDeploymentStatus(deployed);
            status.setAgentId("agent-1");
            status.setAgentVersion(null);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status));

            migration.runIfNeeded();

            verify(agentStore, never()).read(any(), anyInt());
        }

        @Test
        @DisplayName("agent read throws → skipped with warning, other agents still processed")
        void agentReadException() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var status1 = createStatus("agent-bad", 1);
            var status2 = createStatus("agent-good", 1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status1, status2));
            when(agentStore.read("agent-bad", 1)).thenThrow(new RuntimeException("corrupt"));
            setupAgentConfig("agent-good", 1, "slack", "C002", "tok", "sign", null);
            when(descriptorStore.readDescriptor("agent-good", 1)).thenReturn(null);

            migration.runIfNeeded();

            verify(channelStore, times(1)).create(any());
        }

        @Test
        @DisplayName("channelStore.create throws → does not set migration flag")
        void createExceptionRetries() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);
            when(deploymentStore.readDeploymentInfos(deployed))
                    .thenThrow(new RuntimeException("DB unavailable"));

            migration.runIfNeeded();

            // Should NOT set the migration flag (so it retries on next startup)
            verify(migrationLogStore, never()).createMigrationLog(any());
        }
    }

    // ─── Slugify and reserved triggers ────────────────────────────────────────

    @Nested
    @DisplayName("Slugify and reserved triggers")
    class SlugifyAndReserved {

        @Test
        @DisplayName("emoji-only agent name → 'target' fallback")
        void emojiOnlyName() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);
            setupDeployedAgent("agent-1", 1, "slack", "C001", "tok", "sign", null);

            var desc = new DocumentDescriptor();
            desc.setName("🤖💬");
            when(descriptorStore.readDescriptor("agent-1", 1)).thenReturn(desc);

            migration.runIfNeeded();

            var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
            verify(channelStore).create(captor.capture());
            assertEquals("target", captor.getValue().getTargets().get(0).getName());
        }

        @Test
        @DisplayName("agent named 'help' → trigger not assigned (reserved)")
        void reservedTriggerName() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);
            setupDeployedAgent("agent-1", 1, "slack", "C001", "tok", "sign", null);

            var desc = new DocumentDescriptor();
            desc.setName("Help");
            when(descriptorStore.readDescriptor("agent-1", 1)).thenReturn(desc);

            migration.runIfNeeded();

            var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
            verify(channelStore).create(captor.capture());
            var target = captor.getValue().getTargets().get(0);
            assertEquals("help", target.getName());
            assertTrue(target.getTriggers().isEmpty(),
                    "'help' is a reserved keyword — migration should skip it as a trigger");
        }

        @Test
        @DisplayName("duplicate slugified names get numeric suffix")
        void duplicateNamesSuffix() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            // Two agents with same name on same channel
            var connector1 = createConnector("slack", "C001", "tok", "sign", null);
            var connector2 = createConnector("slack", "C001", "tok", "sign", null);

            var agent1 = new AgentConfiguration();
            agent1.setChannels(List.of(connector1));
            var agent2 = new AgentConfiguration();
            agent2.setChannels(List.of(connector2));

            var status1 = createStatus("agent-aaa", 1);
            var status2 = createStatus("agent-bbb", 1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status1, status2));
            when(agentStore.read("agent-aaa", 1)).thenReturn(agent1);
            when(agentStore.read("agent-bbb", 1)).thenReturn(agent2);

            // Both agents have same name
            var desc = new DocumentDescriptor();
            desc.setName("Support Bot");
            when(descriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(desc);

            migration.runIfNeeded();

            var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
            verify(channelStore).create(captor.capture());
            var targets = captor.getValue().getTargets();
            assertEquals(2, targets.size());
            assertEquals("support-bot", targets.get(0).getName());
            assertEquals("support-bot-2", targets.get(1).getName());
        }
    }

    // ─── Credential divergence warning ────────────────────────────────────────

    @Nested
    @DisplayName("Credential divergence")
    class CredentialDivergence {

        @Test
        @DisplayName("agents with different credentials → migration still succeeds (warn only)")
        void divergentCredentials() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var connector1 = createConnector("slack", "C001", "tok-A", "sign-A", null);
            var connector2 = createConnector("slack", "C001", "tok-B", "sign-B", null);

            var agent1 = new AgentConfiguration();
            agent1.setChannels(List.of(connector1));
            var agent2 = new AgentConfiguration();
            agent2.setChannels(List.of(connector2));

            var status1 = createStatus("agent-aaa", 1);
            var status2 = createStatus("agent-bbb", 1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status1, status2));
            when(agentStore.read("agent-aaa", 1)).thenReturn(agent1);
            when(agentStore.read("agent-bbb", 1)).thenReturn(agent2);
            when(descriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(null);

            migration.runIfNeeded();

            // Should still create the config (using first agent's credentials)
            verify(channelStore, times(1)).create(any());
            verify(migrationLogStore).createMigrationLog(any());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void setupDeployedAgent(String agentId, int version, String channelType,
                                    String channelId, String botToken, String signingSecret,
                                    String groupId)
            throws Exception {
        var status = createStatus(agentId, version);
        when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(status));
        setupAgentConfig(agentId, version, channelType, channelId, botToken, signingSecret, groupId);
    }

    private void setupAgentConfig(String agentId, int version, String channelType,
                                  String channelId, String botToken, String signingSecret,
                                  String groupId)
            throws Exception {
        var connector = createConnector(channelType, channelId, botToken, signingSecret, groupId);
        var agentConfig = new AgentConfiguration();
        agentConfig.setChannels(List.of(connector));
        when(agentStore.read(agentId, version)).thenReturn(agentConfig);
    }

    private ChannelConnector createConnector(String type, String channelId,
                                             String botToken, String signingSecret, String groupId) {
        var connector = new ChannelConnector();
        connector.setType(URI.create(type));
        var config = new HashMap<String, String>();
        config.put("channelId", channelId);
        config.put("botToken", botToken);
        config.put("signingSecret", signingSecret);
        if (groupId != null) {
            config.put("groupId", groupId);
        }
        connector.setConfig(config);
        return connector;
    }

    private DeploymentInfo createStatus(String agentId, int version) {
        var status = new DeploymentInfo();
        status.setDeploymentStatus(deployed);
        status.setAgentId(agentId);
        status.setAgentVersion(version);
        return status;
    }
}
