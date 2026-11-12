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
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
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

    /** The completion flag of the run that also writes descriptors. */
    private static final String MIGRATION_KEY = "channel-connector-migration-v2-complete";
    /** The flag of the first run, which created documents without descriptors. */
    private static final String V1_MIGRATION_KEY = "channel-connector-migration-complete";
    private static final String AGENT_ID = "aabbccdd11223344eeff5566";
    private static final String OTHER_AGENT_ID = "bbccddee11223344eeff5566";
    /** The id the channel store hands back for a created channel. */
    private static final String CHANNEL_ID = "ccddeeff11223344aabb5566";

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
     * Stubs descriptorStore.readDescriptor (the agent's descriptor) to return a
     * DocumentDescriptor with the given name.
     */
    private void stubAgentName(String agentId, Integer version, String name) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setName(name);
        doReturn(descriptor).when(descriptorStore).readDescriptor(agentId, version);
    }

    /** What the channel store hands back for a created document. */
    private record CreatedId(String getId, Integer getVersion) implements IResourceStore.IResourceId {
    }

    private void stubCreateReturns(String id, int version) throws Exception {
        doReturn(new CreatedId(id, version)).when(channelStore).create(any(ChannelIntegrationConfiguration.class));
    }

    /** One deployed agent with one legacy Slack connector on the given channel. */
    private void stubAgentWithChannel(String agentId, String channelId) throws Exception {
        var connector = createConnector("slack",
                Map.of("channelId", channelId, "botToken", "xoxb-token", "signingSecret", "secret"));
        doReturn(createAgentConfig(List.of(connector))).when(agentStore).read(agentId, 1);
    }

    // ---- 1. Already migrated ----

    @Test
    @DisplayName("Already migrated — no stores called")
    void runIfNeeded_alreadyMigrated_noop() throws Exception {
        doReturn(new MigrationLog(MIGRATION_KEY))
                .when(migrationLogStore).readMigrationLog(MIGRATION_KEY);

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

        stubCreateReturns(CHANNEL_ID, 1);

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

        stubCreateReturns(CHANNEL_ID, 1);

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

        stubCreateReturns(CHANNEL_ID, 1);

        migration.runIfNeeded();

        var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
        verify(channelStore).create(captor.capture());
        var created = captor.getValue();

        assertEquals("target", created.getTargets().get(0).getName());
        assertEquals(List.of("target"), created.getTargets().get(0).getTriggers());
        assertEquals("target", created.getDefaultTargetName());

        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    // ---- 12. Descriptors: the router, the REST listings and the re-run all find
    // channels through them ----

    @Test
    @DisplayName("Migrated channel gets a descriptor naming it, owned like the agent it routes to")
    void migratedChannelGetsADescriptorNamingIt() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doReturn(List.of(createDeploymentInfo(AGENT_ID, 1))).when(deploymentStore).readDeploymentInfos(deployed);
        stubAgentWithChannel(AGENT_ID, "C12345");
        stubEmptyDescriptors();
        var agentDescriptor = new DocumentDescriptor();
        agentDescriptor.setName("My Agent");
        agentDescriptor.setOwnerId("alice");
        agentDescriptor.setSpaceId("team-a");
        agentDescriptor.setVisibility(ResourceVisibility.published.wireName());
        doReturn(agentDescriptor).when(descriptorStore).readDescriptor(AGENT_ID, 1);
        stubCreateReturns(CHANNEL_ID, 1);

        migration.runIfNeeded();

        var captor = ArgumentCaptor.forClass(DocumentDescriptor.class);
        verify(descriptorStore).createDescriptor(eq(CHANNEL_ID), eq(1), captor.capture());
        var descriptor = captor.getValue();
        assertEquals(URI.create("eddi://ai.labs.channel/channelstore/channels/" + CHANNEL_ID + "?version=1"),
                descriptor.getResource());
        assertEquals("slack — C12345", descriptor.getName());
        assertEquals("alice", descriptor.getOwnerId());
        assertEquals("team-a", descriptor.getSpaceId());
        assertEquals(ResourceVisibility.space, descriptor.resourceVisibility(),
                "a channel holds credentials: publishing the agent for anyone to use does not publish its channel");
        assertNotNull(descriptor.getAccessIndex(), "without its index the channel is missing from workspace listings");
        assertEquals(DescriptorAccess.buildIndex(descriptor), descriptor.getAccessIndex());
        verify(migrationLogStore).createMigrationLog(argThat(log -> MIGRATION_KEY.equals(log.getName())));
    }

    @Test
    @DisplayName("A private agent's channel stays private")
    void privateAgentKeepsItsChannelPrivate() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doReturn(List.of(createDeploymentInfo(AGENT_ID, 1))).when(deploymentStore).readDeploymentInfos(deployed);
        stubAgentWithChannel(AGENT_ID, "C12345");
        stubEmptyDescriptors();
        var agentDescriptor = new DocumentDescriptor();
        agentDescriptor.setOwnerId("alice");
        agentDescriptor.setSpaceId("personal-alice");
        agentDescriptor.setVisibility(ResourceVisibility.privateAccess.wireName());
        doReturn(agentDescriptor).when(descriptorStore).readDescriptor(AGENT_ID, 1);
        stubCreateReturns(CHANNEL_ID, 1);

        migration.runIfNeeded();

        var captor = ArgumentCaptor.forClass(DocumentDescriptor.class);
        verify(descriptorStore).createDescriptor(eq(CHANNEL_ID), eq(1), captor.capture());
        assertEquals(ResourceVisibility.privateAccess, captor.getValue().resourceVisibility());
    }

    @Test
    @DisplayName("A re-run after a partial failure creates only the channel that failed")
    void secondRunSeesTheFirstRunsChannel() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doReturn(List.of(createDeploymentInfo(AGENT_ID, 1), createDeploymentInfo(OTHER_AGENT_ID, 1)))
                .when(deploymentStore).readDeploymentInfos(deployed);
        stubAgentWithChannel(AGENT_ID, "C11111");
        stubAgentWithChannel(OTHER_AGENT_ID, "C22222");

        // Stores that remember what the first run wrote, so the second run reads it
        // back
        var documents = new LinkedHashMap<String, ChannelIntegrationConfiguration>();
        var descriptors = new ArrayList<DocumentDescriptor>();
        var refuseSecondChannel = new boolean[]{true};
        doAnswer(invocation -> {
            ChannelIntegrationConfiguration config = invocation.getArgument(0);
            if (refuseSecondChannel[0] && "C22222".equals(config.getPlatformConfig().get("channelId"))) {
                throw new IResourceStore.ResourceStoreException("write failed");
            }
            String id = String.format("%024x", documents.size() + 1);
            documents.put(id, config);
            return new CreatedId(id, 1);
        }).when(channelStore).create(any(ChannelIntegrationConfiguration.class));
        doAnswer(invocation -> documents.get(invocation.<String>getArgument(0)))
                .when(channelStore).read(anyString(), anyInt());
        doAnswer(invocation -> descriptors.add(invocation.getArgument(2)))
                .when(descriptorStore).createDescriptor(anyString(), anyInt(), any(DocumentDescriptor.class));
        doAnswer(invocation -> List.copyOf(descriptors))
                .when(descriptorStore).readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean());

        migration.runIfNeeded();

        assertEquals(1, documents.size(), "the first run created the channel it could");
        verify(migrationLogStore, never()).createMigrationLog(any());

        refuseSecondChannel[0] = false;
        migration.runIfNeeded();

        assertEquals(List.of("C11111", "C22222"),
                documents.values().stream().map(config -> config.getPlatformConfig().get("channelId")).toList(),
                "the re-run created the channel that failed, and did not create the first one again");
        verify(migrationLogStore).createMigrationLog(argThat(log -> MIGRATION_KEY.equals(log.getName())));
    }

    @Test
    @DisplayName("An agent with no descriptor leaves its channel unowned, as the agent is")
    void agentWithoutDescriptorLeavesTheChannelUnowned() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doReturn(List.of(createDeploymentInfo(AGENT_ID, 1))).when(deploymentStore).readDeploymentInfos(deployed);
        stubAgentWithChannel(AGENT_ID, "C12345");
        stubEmptyDescriptors();
        doThrow(new IResourceStore.ResourceNotFoundException("no descriptor"))
                .when(descriptorStore).readDescriptor(AGENT_ID, 1);
        stubCreateReturns(CHANNEL_ID, 1);

        migration.runIfNeeded();

        var captor = ArgumentCaptor.forClass(DocumentDescriptor.class);
        verify(descriptorStore).createDescriptor(eq(CHANNEL_ID), eq(1), captor.capture());
        assertTrue(DescriptorAccess.isUnowned(captor.getValue()));
        verify(migrationLogStore).createMigrationLog(argThat(log -> MIGRATION_KEY.equals(log.getName())));
    }

    /**
     * Not knowing an agent's owner is not the same as it having none: an unowned
     * channel is open to everyone under the default legacy policy, so a read that
     * failed must not decide that. The run stops and retries; skipping only that
     * agent would create a shared channel without its target, which the retry would
     * then skip as already migrated.
     */
    @Test
    @DisplayName("An agent descriptor that cannot be read aborts the run instead of migrating its channel unowned")
    void unreadableAgentDescriptorAbortsTheRun() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doReturn(List.of(createDeploymentInfo(AGENT_ID, 1))).when(deploymentStore).readDeploymentInfos(deployed);
        stubAgentWithChannel(AGENT_ID, "C12345");
        stubEmptyDescriptors();
        doThrow(new IResourceStore.ResourceStoreException("descriptor store unavailable"))
                .when(descriptorStore).readDescriptor(AGENT_ID, 1);
        stubCreateReturns(CHANNEL_ID, 1);

        migration.runIfNeeded();

        verify(channelStore, never()).create(any());
        verify(migrationLogStore, never()).createMigrationLog(any());
    }

    @Test
    @DisplayName("A descriptor that cannot be written fails the channel, and its document is removed again")
    void descriptorWriteFailureFailsTheChannel() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doReturn(List.of(createDeploymentInfo(AGENT_ID, 1))).when(deploymentStore).readDeploymentInfos(deployed);
        stubAgentWithChannel(AGENT_ID, "C12345");
        stubEmptyDescriptors();
        stubCreateReturns(CHANNEL_ID, 1);
        doThrow(new IResourceStore.ResourceStoreException("descriptor write failed"))
                .when(descriptorStore).createDescriptor(anyString(), anyInt(), any(DocumentDescriptor.class));

        migration.runIfNeeded();

        verify(channelStore).deleteAllPermanently(CHANNEL_ID);
        verify(migrationLogStore, never()).createMigrationLog(any());
    }

    @Test
    @DisplayName("A channel listing that fails aborts the run instead of creating possible duplicates")
    void descriptorListingFailureAbortsInsteadOfCreating() throws Exception {
        doReturn(null).when(migrationLogStore).readMigrationLog(anyString());
        doReturn(List.of(createDeploymentInfo(AGENT_ID, 1))).when(deploymentStore).readDeploymentInfos(deployed);
        stubAgentWithChannel(AGENT_ID, "C12345");
        doThrow(new IResourceStore.ResourceStoreException("listing failed"))
                .when(descriptorStore).readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());

        migration.runIfNeeded();

        verify(channelStore, never()).create(any());
        verify(migrationLogStore, never()).createMigrationLog(any());
    }

    @Test
    @DisplayName("A deployment that completed the first run, which wrote no descriptors, runs again")
    void firstRunsFlagDoesNotSkipTheDescriptorRun() throws Exception {
        doReturn(new MigrationLog(V1_MIGRATION_KEY)).when(migrationLogStore).readMigrationLog(V1_MIGRATION_KEY);
        doReturn(Collections.emptyList()).when(deploymentStore).readDeploymentInfos(deployed);
        stubEmptyDescriptors();

        migration.runIfNeeded();

        verify(deploymentStore).readDeploymentInfos(deployed);
        verify(migrationLogStore).createMigrationLog(argThat(log -> MIGRATION_KEY.equals(log.getName())));
    }
}
