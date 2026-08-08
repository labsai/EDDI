/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ArtifactToolsProvider} (I17). Same negative-gate discipline
 * as {@link GroupTaskToolsProviderTest}: this provider hands an LLM a write
 * surface, so every way of being unsure resolves to "contribute nothing".
 *
 * @author tests
 */
class ArtifactToolsProviderTest {

    private static final String GC_ID = "gc-1";
    private static final String GROUP_ID = "group-1";
    private static final String MEMBER_CONV = "member-conv-1";

    private LiveDiscussionRegistry registry;
    private IAgentGroupStore groupStore;
    private ISharedArtifactStore artifactStore;

    @BeforeEach
    void setUp() throws Exception {
        registry = new LiveDiscussionRegistry();
        groupStore = mock(IAgentGroupStore.class);
        artifactStore = mock(ISharedArtifactStore.class);
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.getMemberConversationIds().put("agent-a", MEMBER_CONV);
        registry.register(gc);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resourceId());
    }

    private IResourceStore.IResourceId resourceId() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    private AgentGroupConfiguration groupWith(ArtifactConfig artifactConfig) {
        var config = new AgentGroupConfiguration();
        config.setName("G");
        config.setArtifactConfig(artifactConfig);
        return config;
    }

    private ToolAssemblyContext ctx(String groupConversationId) {
        return ctx(groupConversationId, MEMBER_CONV, true);
    }

    private ToolAssemblyContext ctx(String groupConversationId, String callerConversationId, boolean builtInsEnabled) {
        var memory = mock(IConversationMemory.class);
        lenient().when(memory.getConversationId()).thenReturn(callerConversationId);
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(builtInsEnabled);
        return new ToolAssemblyContext(memory, task, null, null, "user-1", "agent-a", groupConversationId);
    }

    private ArtifactToolsProvider provider() {
        return new ArtifactToolsProvider(registry, groupStore, artifactStore);
    }

    @Test
    void enabledGroupDiscussion_contributesAllFourTools() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(new ArtifactConfig(true, 5, List.of())));

        var contribution = provider().contribute(ctx(GC_ID));

        assertEquals(4, contribution.specs().size(), "createArtifact, readArtifact, proposeArtifactUpdate, listArtifacts");
        for (String tool : List.of("createArtifact", "readArtifact", "proposeArtifactUpdate", "listArtifacts")) {
            assertTrue(contribution.specs().stream().anyMatch(spec -> spec.name().equals(tool)), tool);
        }
    }

    @Test
    void notAGroupTurn_contributesNothing() {
        assertTrue(provider().contribute(ctx(null)).specs().isEmpty());
    }

    @Test
    void discussionNotLive_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(new ArtifactConfig(true, 5, List.of())));
        registry.unregister(GC_ID);

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty());
    }

    @Test
    void noArtifactConfig_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(null));

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty(),
                "an absent policy is not a permissive policy");
    }

    @Test
    void featureDisabled_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(new ArtifactConfig()));

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty(),
                "the default ArtifactConfig is off, and off must mean absent");
    }

    @Test
    void unreadableGroup_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("mongo is down"));

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty());
    }

    @Test
    void missingGroup_contributesNothing() throws Exception {
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(null);

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty());
        verify(groupStore, never()).read(anyString(), anyInt());
    }

    @Test
    void missingDependencies_contributeNothing() {
        assertTrue(new ArtifactToolsProvider(null, groupStore, artifactStore).contribute(ctx(GC_ID)).specs().isEmpty());
        assertTrue(new ArtifactToolsProvider(registry, null, artifactStore).contribute(ctx(GC_ID)).specs().isEmpty());
        assertTrue(new ArtifactToolsProvider(registry, groupStore, null).contribute(ctx(GC_ID)).specs().isEmpty());
    }

    @Test
    void anotherUsersDiscussion_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(new ArtifactConfig(true, 5, List.of())));

        assertTrue(provider().contribute(ctx(GC_ID, "not-a-member-conversation", true)).specs().isEmpty(),
                "membership must be checked, not merely discussion existence");
        assertTrue(provider().contribute(ctx(GC_ID, null, true)).specs().isEmpty());
    }

    @Test
    void agentWithBuiltInToolsDisabled_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(new ArtifactConfig(true, 5, List.of())));

        assertTrue(provider().contribute(ctx(GC_ID, MEMBER_CONV, false)).specs().isEmpty());
    }

    @Test
    void source_isBuiltin() {
        assertEquals("builtin", provider().source());
    }
}
