/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.PromotionImpact;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the next version of an agent — an import or a sync onto it — would do to
 * the conversations running on it, before the operator declares it compatible
 * or not. Counts only; the preview must never load a conversation.
 */
@DisplayName("RestAgentAdministration — promotion impact preview")
class RestAgentAdministrationPromotionImpactTest {

    private static final String AGENT = "agent-1";

    private IAgentStore agentStore;
    private IDeploymentStore deploymentStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IDocumentDescriptorStore descriptorStore;
    private ResourceAccessGuard accessGuard;
    private RestAgentAdministration admin;
    private final List<DeploymentInfo> records = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        deploymentStore = mock(IDeploymentStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        when(accessGuard.hasAccess(AGENT, AccessLevel.EDIT)).thenReturn(true);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(records);
        when(agentFactory.getAllDeployedAgents(any())).thenReturn(List.of());
        when(conversationMemoryStore.getActiveConversationCount(anyString(), any())).thenReturn(0L);
        admin = new RestAgentAdministration(mock(IRuntime.class), agentFactory, agentStore, deploymentStore, conversationMemoryStore,
                mock(IRestConversationStore.class), descriptorStore, mock(IDeploymentListener.class), mock(IScheduleStore.class),
                mock(TenantQuotaService.class), accessGuard);
    }

    @Test
    @DisplayName("a compatible promotion lets same-generation conversations follow; a breaking one pins every conversation")
    void bothDeclarationsAreAnswered() throws Exception {
        currentVersion(4, 2);
        version(3, 2);
        version(2, 1);
        deployed(Deployment.Environment.production, 4);
        deployed(Deployment.Environment.production, 3);
        deployed(Deployment.Environment.production, 2);
        active(4, 10);
        active(3, 3);
        active(2, 4);

        PromotionImpact impact = admin.previewNextVersion(AGENT);

        assertEquals(4, impact.currentVersion());
        assertEquals(2, impact.currentGeneration());
        assertEquals(2, impact.generationIfCompatible(), "compatible keeps the generation");
        assertEquals(3, impact.generationIfBreaking(), "breaking starts the next one");
        assertFalse(impact.startsNewChain());
        assertEquals(17, impact.activeConversations());
        // v4 and v3 share generation 2 with the compatible successor; v2 is another one
        assertEquals(13, impact.followingIfCompatible());
        assertEquals(List.of(4, 3, 2), impact.deployedVersions().stream().map(PromotionImpact.DeployedVersion::version).toList());
        assertEquals(List.of(true, true, false),
                impact.deployedVersions().stream().map(PromotionImpact.DeployedVersion::followsIfCompatible).toList());
    }

    @Test
    @DisplayName("a current version without a generation starts a chain: even a compatible save pins what runs")
    void legacyCurrentVersionStartsAChain() throws Exception {
        currentVersion(4, null);
        deployed(Deployment.Environment.production, 4);
        active(4, 6);

        PromotionImpact impact = admin.previewNextVersion(AGENT);

        assertTrue(impact.startsNewChain());
        assertNull(impact.currentGeneration());
        assertEquals(1, impact.generationIfCompatible(), "the same rule AgentStore applies on the write");
        assertEquals(1, impact.generationIfBreaking());
        assertEquals(6, impact.activeConversations());
        assertEquals(0, impact.followingIfCompatible(), "nothing follows a version that has no chain");
    }

    @Test
    @DisplayName("a version deployed in both environments is counted once: conversations are counted per version")
    void versionInBothEnvironmentsIsCountedOnce() throws Exception {
        currentVersion(4, 2);
        deployed(Deployment.Environment.production, 4);
        deployed(Deployment.Environment.test, 4);
        active(4, 7);

        PromotionImpact impact = admin.previewNextVersion(AGENT);

        assertEquals(7, impact.activeConversations());
        assertEquals(1, impact.deployedVersions().size());
    }

    @Test
    @DisplayName("only counts are read: no conversation is ever loaded")
    void onlyCountsAreRead() throws Exception {
        currentVersion(4, 2);
        deployed(Deployment.Environment.production, 4);
        active(4, 100_000);

        admin.previewNextVersion(AGENT);

        verify(conversationMemoryStore).getActiveConversationCount(AGENT, 4);
        verify(conversationMemoryStore, never()).loadActiveConversationMemorySnapshot(anyString(), any());
    }

    @Test
    @DisplayName("a caller without EDIT on the agent gets no counts, and nothing is read")
    void withoutEditThereAreNoCounts() throws Exception {
        currentVersion(4, 2);
        when(accessGuard.hasAccess(AGENT, AccessLevel.EDIT)).thenReturn(false);

        assertNull(admin.previewNextVersion(AGENT));

        verify(conversationMemoryStore, never()).getActiveConversationCount(anyString(), any());
    }

    @Test
    @DisplayName("an agent whose current version cannot be read has no impact to report")
    void unreadableAgentHasNoImpact() throws Exception {
        when(descriptorStore.getCurrentResourceId(AGENT)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        assertNull(admin.previewNextVersion(AGENT));
    }

    private void currentVersion(int version, Integer generation) throws Exception {
        IResourceId id = new IResourceId() {
            @Override
            public String getId() {
                return AGENT;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
        when(descriptorStore.getCurrentResourceId(AGENT)).thenReturn(id);
        version(version, generation);
    }

    private void version(int version, Integer generation) throws Exception {
        var config = new AgentConfiguration();
        config.setCompatibilityGeneration(generation);
        when(agentStore.read(AGENT, version)).thenReturn(config);
    }

    private void deployed(Deployment.Environment environment, int version) {
        var info = new DeploymentInfo();
        info.setAgentId(AGENT);
        info.setAgentVersion(version);
        info.setEnvironment(environment);
        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        records.add(info);
    }

    private void active(int version, long count) {
        when(conversationMemoryStore.getActiveConversationCount(AGENT, version)).thenReturn(count);
    }
}
