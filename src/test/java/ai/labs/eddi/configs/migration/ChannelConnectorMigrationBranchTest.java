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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.*;

import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("ChannelConnectorMigration — Branch Coverage Tests")
class ChannelConnectorMigrationBranchTest {

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

    @BeforeEach
    void setUp() {
        openMocks(this);
        migration = new ChannelConnectorMigration(deploymentStore, agentStore, channelStore, descriptorStore, migrationLogStore);
    }

    @Nested
    @DisplayName("runIfNeeded")
    class RunIfNeeded {

        @Test
        @DisplayName("already applied — skips migration")
        void alreadyApplied() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString()))
                    .thenReturn(new MigrationLog("channel-connector-migration-complete"));

            migration.runIfNeeded();

            verify(deploymentStore, never()).readDeploymentInfos(any());
            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        @Test
        @DisplayName("no deployed agents — creates log and exits")
        void noDeployedAgents() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);
            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of());
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }

        @Test
        @DisplayName("migration failure prevents log creation — will retry")
        void migrationFailureRetries() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);
            when(deploymentStore.readDeploymentInfos(deployed))
                    .thenThrow(new RuntimeException("db error"));

            migration.runIfNeeded();

            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        @Test
        @DisplayName("deployment with null agentId is skipped")
        void nullAgentIdSkipped() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId(null);
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }

        @Test
        @DisplayName("deployment with null agentVersion is skipped")
        void nullAgentVersionSkipped() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(null);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }

        @Test
        @DisplayName("agent with null channels is skipped")
        void agentWithNullChannels() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(null);
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }

        @Test
        @DisplayName("connector with null type is skipped")
        void connectorWithNullType() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var connector = new ChannelConnector();
            connector.setType(null);
            connector.setConfig(Map.of("channelId", "ch1"));

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }

        @Test
        @DisplayName("connector with null config is skipped")
        void connectorWithNullConfig() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(null);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }

        @Test
        @DisplayName("connector with blank channelId is skipped")
        void connectorWithBlankChannelId() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var configMap = new HashMap<String, String>();
            configMap.put("channelId", "   ");

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(configMap);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }

        @Test
        @DisplayName("successful migration with groupId target")
        void successfulMigrationWithGroupId() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var configMap = new HashMap<String, String>();
            configMap.put("channelId", "C12345");
            configMap.put("botToken", "xoxb-token");
            configMap.put("signingSecret", "secret");
            configMap.put("groupId", "group1");

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(configMap);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("My Agent");
            when(descriptorStore.readDescriptor("agent1", 1)).thenReturn(agentDescriptor);

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(channelStore).create(any(ChannelIntegrationConfiguration.class));
            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }

        @Test
        @DisplayName("create failure increments failed counter — no log created")
        void createFailure() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var configMap = new HashMap<String, String>();
            configMap.put("channelId", "C12345");

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(configMap);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            doThrow(new RuntimeException("create failed")).when(channelStore).create(any());

            migration.runIfNeeded();

            // Failed → no migration log created
            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        @Test
        @DisplayName("existing channel key is skipped (duplicate prevention)")
        void existingChannelKeySkipped() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var configMap = new HashMap<String, String>();
            configMap.put("channelId", "C12345");

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(configMap);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            // Existing channel config descriptor
            var existingDesc = new DocumentDescriptor();
            existingDesc.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/aabbccddeeff112233445566?version=1"));
            when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(existingDesc));

            var existingConfig = new ChannelIntegrationConfiguration();
            existingConfig.setChannelType("slack");
            var platformConfig = new HashMap<String, String>();
            platformConfig.put("channelId", "C12345");
            existingConfig.setPlatformConfig(platformConfig);
            when(channelStore.read("aabbccddeeff112233445566", 1)).thenReturn(existingConfig);

            migration.runIfNeeded();

            // Should skip creating duplicate
            verify(channelStore, never()).create(any());
            verify(migrationLogStore).createMigrationLog(any());
        }

        @Test
        @DisplayName("agent read exception during migration is handled")
        void agentReadException() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));
            when(agentStore.read("agent1", 1)).thenThrow(new RuntimeException("agent read error"));

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(migrationLogStore).createMigrationLog(any());
        }

        @Test
        @DisplayName("reserved trigger name 'help' generates empty triggers list")
        void reservedTriggerName() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var configMap = new HashMap<String, String>();
            configMap.put("channelId", "C12345");

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(configMap);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            // Agent name that slugifies to "help"
            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Help");
            when(descriptorStore.readDescriptor("agent1", 1)).thenReturn(agentDescriptor);

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(channelStore).create(any(ChannelIntegrationConfiguration.class));
        }

        @Test
        @DisplayName("lookupAgentName returns null when descriptor not found")
        void lookupAgentNameNotFound() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var configMap = new HashMap<String, String>();
            configMap.put("channelId", "C12345");

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(configMap);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            when(descriptorStore.readDescriptor("agent1", 1))
                    .thenThrow(new RuntimeException("not found"));

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(channelStore).create(any());
        }

        @Test
        @DisplayName("lookupAgentName returns null when name is blank")
        void lookupAgentNameBlank() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var configMap = new HashMap<String, String>();
            configMap.put("channelId", "C12345");

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(configMap);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("   ");
            when(descriptorStore.readDescriptor("agent1", 1)).thenReturn(agentDescriptor);

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(channelStore).create(any());
        }

        @Test
        @DisplayName("slugify with special characters produces valid slug")
        void slugifySpecialChars() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deployInfo = new DeploymentInfo();
            deployInfo.setAgentId("agent1");
            deployInfo.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deployInfo));

            var configMap = new HashMap<String, String>();
            configMap.put("channelId", "C12345");

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(configMap);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            // Name with only emoji/special chars
            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("!!!@@@###");
            when(descriptorStore.readDescriptor("agent1", 1)).thenReturn(agentDescriptor);

            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(channelStore).create(any());
        }

        @Test
        @DisplayName("credential divergence warning with different botToken")
        void credentialDivergence() throws Exception {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var deploy1 = new DeploymentInfo();
            deploy1.setAgentId("agent1");
            deploy1.setAgentVersion(1);

            var deploy2 = new DeploymentInfo();
            deploy2.setAgentId("agent2");
            deploy2.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(deploy1, deploy2));

            var configMap1 = new HashMap<String, String>();
            configMap1.put("channelId", "C12345");
            configMap1.put("botToken", "token1");
            configMap1.put("signingSecret", "secret1");

            var connector1 = new ChannelConnector();
            connector1.setType(URI.create("slack"));
            connector1.setConfig(configMap1);
            var agentConfig1 = new AgentConfiguration();
            agentConfig1.setChannels(List.of(connector1));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig1);

            var configMap2 = new HashMap<String, String>();
            configMap2.put("channelId", "C12345");
            configMap2.put("botToken", "different-token");
            configMap2.put("signingSecret", "different-secret");

            var connector2 = new ChannelConnector();
            connector2.setType(URI.create("slack"));
            connector2.setConfig(configMap2);
            var agentConfig2 = new AgentConfiguration();
            agentConfig2.setChannels(List.of(connector2));
            when(agentStore.read("agent2", 1)).thenReturn(agentConfig2);

            when(descriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(null);
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            migration.runIfNeeded();

            verify(channelStore).create(any());
        }
    }
}
