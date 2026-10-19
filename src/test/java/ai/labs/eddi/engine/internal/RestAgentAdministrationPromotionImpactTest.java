/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * or not.
 * <p>
 * Conversations are counted by their own compatibility generation, one grouped
 * count and never the conversations: a conversation follows the newest ready
 * version of its generation, so one stored on a version that is no longer
 * deployed still follows, and a count per deployed version would miss it.
 */
@DisplayName("RestAgentAdministration — promotion impact preview")
class RestAgentAdministrationPromotionImpactTest {

    private static final String AGENT = "agent-1";

    private IAgentStore agentStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IDocumentDescriptorStore descriptorStore;
    private ResourceAccessGuard accessGuard;
    private RestAgentAdministration admin;
    private final Map<Integer, Long> byGeneration = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IAgentStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        when(accessGuard.hasAccess(AGENT, AccessLevel.EDIT)).thenReturn(true);
        when(conversationMemoryStore.getActiveConversationCountsByGeneration(AGENT)).thenReturn(byGeneration);
        admin = new RestAgentAdministration(mock(IRuntime.class), mock(IAgentFactory.class), agentStore, mock(IDeploymentStore.class),
                conversationMemoryStore, mock(IRestConversationStore.class), descriptorStore, mock(IDeploymentListener.class),
                mock(IScheduleStore.class), mock(TenantQuotaService.class), accessGuard);
    }

    @Test
    @DisplayName("a compatible promotion lets the current generation follow, wherever its conversations are stored; older ones stay")
    void currentGenerationFollowsOthersStay() throws Exception {
        currentVersion(4, 2);
        byGeneration.put(2, 347L); // idle conversations stored on versions that are no longer deployed
        byGeneration.put(1, 4L);
        byGeneration.put(null, 2L);

        PromotionImpact impact = admin.previewNextVersion(AGENT);

        assertEquals(4, impact.currentVersion());
        assertEquals(2, impact.currentGeneration());
        assertEquals(2, impact.generationIfCompatible(), "compatible keeps the generation");
        assertEquals(3, impact.generationIfBreaking(), "breaking starts the next one");
        assertFalse(impact.startsNewChain());
        assertEquals(353, impact.activeConversations());
        assertEquals(347, impact.followingIfCompatible());
        assertEquals(Arrays.asList(2, 1, null), impact.generations().stream().map(PromotionImpact.GenerationCount::generation).toList());
        assertEquals(List.of(true, false, false),
                impact.generations().stream().map(PromotionImpact.GenerationCount::followsIfCompatible).toList());
    }

    @Test
    @DisplayName("a current version without a generation starts a chain: nothing follows, even when declared compatible")
    void legacyCurrentVersionStartsAChain() throws Exception {
        currentVersion(4, null);
        // generation 1 is what the compatible save would produce; conversations on it
        // must still not be promised anything, because the chain starts fresh
        byGeneration.put(1, 3L);
        byGeneration.put(null, 6L);

        PromotionImpact impact = admin.previewNextVersion(AGENT);

        assertTrue(impact.startsNewChain());
        assertNull(impact.currentGeneration());
        assertEquals(1, impact.generationIfCompatible(), "the same rule AgentStore applies on the write");
        assertEquals(1, impact.generationIfBreaking());
        assertEquals(9, impact.activeConversations());
        assertEquals(0, impact.followingIfCompatible());
    }

    @Test
    @DisplayName("a conversation without a generation always stays")
    void conversationWithoutGenerationStays() throws Exception {
        currentVersion(4, 2);
        byGeneration.put(null, 5L);

        PromotionImpact impact = admin.previewNextVersion(AGENT);

        assertEquals(5, impact.activeConversations());
        assertEquals(0, impact.followingIfCompatible());
    }

    @Test
    @DisplayName("no open conversations is an answer, not an absence")
    void noConversations() throws Exception {
        currentVersion(4, 2);

        PromotionImpact impact = admin.previewNextVersion(AGENT);

        assertEquals(0, impact.activeConversations());
        assertTrue(impact.generations().isEmpty());
    }

    @Test
    @DisplayName("only one grouped count is read: no per-version count, no conversation is loaded")
    void onlyOneGroupedCountIsRead() throws Exception {
        currentVersion(4, 2);
        byGeneration.put(2, 100_000L);

        admin.previewNextVersion(AGENT);

        verify(conversationMemoryStore).getActiveConversationCountsByGeneration(AGENT);
        verify(conversationMemoryStore, never()).getActiveConversationCount(anyString(), any());
        verify(conversationMemoryStore, never()).loadActiveConversationMemorySnapshot(anyString(), any());
    }

    @Test
    @DisplayName("a caller without EDIT on the agent gets no counts, and nothing is read")
    void withoutEditThereAreNoCounts() throws Exception {
        currentVersion(4, 2);
        when(accessGuard.hasAccess(AGENT, AccessLevel.EDIT)).thenReturn(false);

        assertNull(admin.previewNextVersion(AGENT));

        verify(conversationMemoryStore, never()).getActiveConversationCountsByGeneration(anyString());
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
        var config = new AgentConfiguration();
        config.setCompatibilityGeneration(generation);
        when(agentStore.read(AGENT, version)).thenReturn(config);
    }
}
