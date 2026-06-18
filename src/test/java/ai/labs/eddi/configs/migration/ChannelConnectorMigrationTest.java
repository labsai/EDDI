/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.net.URI;
import java.util.*;

import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class ChannelConnectorMigrationTest {

    @Mock
    private IDeploymentStore deploymentStore;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private IChannelIntegrationStore channelStore;
    @Mock
    private IDocumentDescriptorStore descriptorStore;
    @Mock
    private IMigrationLogStore migrationLogStore;

    private ChannelConnectorMigration migration;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        migration = new ChannelConnectorMigration(
                deploymentStore, agentStore, channelStore, descriptorStore, migrationLogStore);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    // ---- Helpers ----

    private DeploymentInfo createDeploymentInfo(String agentId, Integer version) {
        var info = new DeploymentInfo();
        info.setAgentId(agentId);
        info.setAgentVersion(version);
        return info;
    }

    private ChannelConnector createConnector(String typeUri, Map<String, String> config) {
        var connector = new ChannelConnector();
        connector.setType(typeUri != null ? URI.create(typeUri) : null);
        connector.setConfig(config);
        return connector;
    }

    private AgentConfiguration createAgentConfig(List<ChannelConnector> channels) {
        var agentConfig = new AgentConfiguration();
        agentConfig.setChannels(channels);
        return agentConfig;
    }

    /**
     * Stubs descriptorStore.readDescriptors to return an empty list (avoids NPE in
     * loadExistingChannelKeys).
     */
    private void stubEmptyDescriptors() throws Exception {
        doReturn(Collections.emptyList())
                .when(descriptorStore).readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
    }

    /**
     * Stubs descriptorStore.readDescriptor (for lookupAgentName) to return a
     * DocumentDescriptor with the given name.
     */
    private void stubAgentName(String agentId, Integer version, String name) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setName(name);
        doReturn(descriptor).when(descriptorStore).readDescriptor(agentId, version);
    }

    // ---- 1. Already migrated ----

    @Test
    @DisplayName("Already migrated — no stores called")
    void runIfNeeded_alreadyMigrated_noop() throws Exception {
        doReturn(new MigrationLog("channel-connector-migration-complete"))
                .when(migrationLogStore).readMigrationLog("channel-connector-migration-complete");

        migration.runIfNeeded();

        verifyNoInteractions(deploymentStore);
        verifyNoInteractions(agentStore);
        verifyNoInteractions(channelStore);
        verify(migrationLogStore, never()).createMigrationLog(any());
    }

    // ---- 2. No deployed agents ----

    @Test
    @DisplayName("No deployed agents — migration completes and flag is set")
    void runIfNeeded_noDeployedAgents_setsFlag() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doReturn(Collections.emptyList()).when(deploymentStore).readDeploymentInfos(deployed);
        stubEmptyDescriptors();

        migration.runIfNeeded();

        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        verifyNoInteractions(agentStore);
        verifyNoInteractions(channelStore);
    }

    // ---- 3. Agent with null agentId ----

    @Test
    @DisplayName("Agent with null agentId — skipped")
    void runIfNeeded_nullAgentId_skipped() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doReturn(List.of(createDeploymentInfo(null, 1)))
                .when(deploymentStore).readDeploymentInfos(deployed);
        stubEmptyDescriptors();

        migration.runIfNeeded();

        verifyNoInteractions(agentStore);
        verifyNoInteractions(channelStore);
        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    // ---- 4. Agent with null channels ----

    @Test
    @DisplayName("Agent config with null channels — skipped")
    void runIfNeeded_nullChannels_skipped() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        var info = createDeploymentInfo("aabbccdd11223344eeff5566", 1);
        doReturn(List.of(info)).when(deploymentStore).readDeploymentInfos(deployed);

        var agentConfig = new AgentConfiguration();
        agentConfig.setChannels(null);
        doReturn(agentConfig).when(agentStore).read("aabbccdd11223344eeff5566", 1);
        stubEmptyDescriptors();

        migration.runIfNeeded();

        verifyNoInteractions(channelStore);
        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    // ---- 5. Channel with null type ----

    @Test
    @DisplayName("Channel with null type — skipped")
    void runIfNeeded_nullChannelType_skipped() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        var info = createDeploymentInfo("aabbccdd11223344eeff5566", 1);
        doReturn(List.of(info)).when(deploymentStore).readDeploymentInfos(deployed);

        var connector = createConnector(null, Map.of("channelId", "C12345"));
        var agentConfig = createAgentConfig(List.of(connector));
        doReturn(agentConfig).when(agentStore).read("aabbccdd11223344eeff5566", 1);
        stubEmptyDescriptors();

        migration.runIfNeeded();

        verifyNoInteractions(channelStore);
        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    // ---- 6. Channel with blank channelId ----

    @Test
    @DisplayName("Channel with blank channelId — skipped")
    void runIfNeeded_blankChannelId_skipped() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        var info = createDeploymentInfo("aabbccdd11223344eeff5566", 1);
        doReturn(List.of(info)).when(deploymentStore).readDeploymentInfos(deployed);

        var connector = createConnector("slack", Map.of("channelId", "  "));
        var agentConfig = createAgentConfig(List.of(connector));
        doReturn(agentConfig).when(agentStore).read("aabbccdd11223344eeff5566", 1);
        stubEmptyDescriptors();

        migration.runIfNeeded();

        verifyNoInteractions(channelStore);
        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    // ---- 7. Single agent, single channel (happy path) ----

    @Test
    @DisplayName("Single agent with one channel — creates ChannelIntegrationConfiguration and sets flag")
    void runIfNeeded_singleAgentSingleChannel_createsConfig() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        var info = createDeploymentInfo("aabbccdd11223344eeff5566", 1);
        doReturn(List.of(info)).when(deploymentStore).readDeploymentInfos(deployed);

        var connector = createConnector("slack",
                Map.of("channelId", "C12345", "botToken", "xoxb-token", "signingSecret", "secret"));
        var agentConfig = createAgentConfig(List.of(connector));
        doReturn(agentConfig).when(agentStore).read("aabbccdd11223344eeff5566", 1);
        stubEmptyDescriptors();

        stubAgentName("aabbccdd11223344eeff5566", 1, "My Agent");

        var mockResourceId = mock(IResourceStore.IResourceId.class);
        doReturn("generatedId").when(mockResourceId).getId();
        doReturn(mockResourceId).when(channelStore).create(any(ChannelIntegrationConfiguration.class));

        migration.runIfNeeded();

        var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
        verify(channelStore).create(captor.capture());
        var created = captor.getValue();

        assertEquals("slack", created.getChannelType());
        assertNotNull(created.getPlatformConfig());
        assertEquals("C12345", created.getPlatformConfig().get("channelId"));
        assertEquals("xoxb-token", created.getPlatformConfig().get("botToken"));
        assertEquals("secret", created.getPlatformConfig().get("signingSecret"));
        assertNotNull(created.getTargets());
        assertEquals(1, created.getTargets().size());
        assertEquals("my-agent", created.getTargets().get(0).getName());
        assertEquals("aabbccdd11223344eeff5566", created.getTargets().get(0).getTargetId());
        assertEquals(List.of("my-agent"), created.getTargets().get(0).getTriggers());
        assertEquals("my-agent", created.getDefaultTargetName());

        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    // ---- 8. Exception during migration ----

    @Test
    @DisplayName("Exception during migration — flag NOT set")
    void runIfNeeded_exceptionDuringMigration_flagNotSet() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doThrow(new RuntimeException("DB connection lost"))
                .when(deploymentStore).readDeploymentInfos(deployed);

        migration.runIfNeeded();

        verify(migrationLogStore, never()).createMigrationLog(any());
    }

    // ---- 9. Channel creation fails (failed > 0) — flag NOT set (retry) ----

    @Test
    @DisplayName("Channel creation fails — flag NOT set so migration retries")
    void runIfNeeded_channelCreationFails_flagNotSet() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        var info = createDeploymentInfo("aabbccdd11223344eeff5566", 1);
        doReturn(List.of(info)).when(deploymentStore).readDeploymentInfos(deployed);

        var connector = createConnector("slack",
                Map.of("channelId", "C12345", "botToken", "xoxb-token", "signingSecret", "secret"));
        var agentConfig = createAgentConfig(List.of(connector));
        doReturn(agentConfig).when(agentStore).read("aabbccdd11223344eeff5566", 1);
        stubEmptyDescriptors();

        doThrow(new IResourceStore.ResourceStoreException("write failed"))
                .when(channelStore).create(any(ChannelIntegrationConfiguration.class));

        migration.runIfNeeded();

        verify(migrationLogStore, never()).createMigrationLog(any());
    }

    // ---- 10. Reserved trigger 'help' ----

    @Test
    @DisplayName("Reserved trigger 'help' — trigger list is empty")
    void runIfNeeded_reservedTriggerHelp_emptyTriggers() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        var info = createDeploymentInfo("aabbccdd11223344eeff5566", 1);
        doReturn(List.of(info)).when(deploymentStore).readDeploymentInfos(deployed);

        var connector = createConnector("slack",
                Map.of("channelId", "C12345", "botToken", "xoxb-token", "signingSecret", "secret"));
        var agentConfig = createAgentConfig(List.of(connector));
        doReturn(agentConfig).when(agentStore).read("aabbccdd11223344eeff5566", 1);
        stubEmptyDescriptors();

        // Agent name that slugifies to "help"
        stubAgentName("aabbccdd11223344eeff5566", 1, "Help");

        var mockResourceId = mock(IResourceStore.IResourceId.class);
        doReturn(mockResourceId).when(channelStore).create(any(ChannelIntegrationConfiguration.class));

        migration.runIfNeeded();

        var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
        verify(channelStore).create(captor.capture());
        var created = captor.getValue();

        assertEquals("help", created.getTargets().get(0).getName());
        assertEquals(List.of(), created.getTargets().get(0).getTriggers());

        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    // ---- 11. Slugify with special chars — gets 'target' fallback ----

    @Test
    @DisplayName("Slugify with emoji/special chars — falls back to 'target'")
    void runIfNeeded_slugifySpecialChars_fallbackTarget() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        var info = createDeploymentInfo("aabbccdd11223344eeff5566", 1);
        doReturn(List.of(info)).when(deploymentStore).readDeploymentInfos(deployed);

        var connector = createConnector("slack",
                Map.of("channelId", "C12345", "botToken", "xoxb-token", "signingSecret", "secret"));
        var agentConfig = createAgentConfig(List.of(connector));
        doReturn(agentConfig).when(agentStore).read("aabbccdd11223344eeff5566", 1);
        stubEmptyDescriptors();

        // Agent name with only emoji/special characters → slugifies to empty → fallback
        // "target"
        stubAgentName("aabbccdd11223344eeff5566", 1, "\uD83E\uDD16\uD83D\uDE80\u2728");

        var mockResourceId = mock(IResourceStore.IResourceId.class);
        doReturn(mockResourceId).when(channelStore).create(any(ChannelIntegrationConfiguration.class));

        migration.runIfNeeded();

        var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
        verify(channelStore).create(captor.capture());
        var created = captor.getValue();

        assertEquals("target", created.getTargets().get(0).getName());
        assertEquals(List.of("target"), created.getTargets().get(0).getTriggers());
        assertEquals("target", created.getDefaultTargetName());

        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }
}
