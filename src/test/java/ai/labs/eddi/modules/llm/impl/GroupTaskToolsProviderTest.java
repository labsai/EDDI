/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupTaskConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GroupTaskToolsProvider} (I5).
 * <p>
 * Every test here is a negative one, and that is the point. This provider hands
 * an LLM a write tool into a shared task list, so each way of being unsure — no
 * group, not live, unreadable config, store down — has to resolve to
 * "contribute nothing". A gate that degrades open on any one of them is
 * indistinguishable from no gate at all for the deployment that trips it.
 *
 * @author tests
 */
class GroupTaskToolsProviderTest {

    private static final String GC_ID = "gc-1";
    private static final String GROUP_ID = "group-1";

    private LiveDiscussionRegistry registry;
    private IAgentGroupStore groupStore;

    @BeforeEach
    void setUp() throws Exception {
        registry = new LiveDiscussionRegistry();
        groupStore = mock(IAgentGroupStore.class);
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
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

    private AgentGroupConfiguration groupWith(GroupTaskConfig taskListConfig) {
        var config = new AgentGroupConfiguration();
        config.setName("G");
        config.setTaskListConfig(taskListConfig);
        return config;
    }

    private ToolAssemblyContext ctx(String groupConversationId) {
        return new ToolAssemblyContext(null, null, null, null, "user-1", "agent-a", groupConversationId);
    }

    private GroupTaskToolsProvider provider() {
        return new GroupTaskToolsProvider(registry, groupStore);
    }

    @Test
    void enabledGroupDiscussion_contributesBothTools() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(new GroupTaskConfig(true, 20, 3)));

        var contribution = provider().contribute(ctx(GC_ID));

        assertEquals(2, contribution.specs().size(), "addGroupTask and listGroupTasks");
        assertTrue(contribution.specs().stream().anyMatch(spec -> spec.name().equals("addGroupTask")));
        assertTrue(contribution.specs().stream().anyMatch(spec -> spec.name().equals("listGroupTasks")));
    }

    @Test
    void notAGroupTurn_contributesNothing() {
        // A standalone agent has no group conversation id. Reading that as
        // "unrestricted" would hand the tools to every agent in the deployment.
        assertTrue(provider().contribute(ctx(null)).specs().isEmpty());
    }

    @Test
    void discussionNotLive_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(new GroupTaskConfig(true, 20, 3)));
        registry.unregister(GC_ID);

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty(),
                "a paused or finished discussion cannot absorb a write");
    }

    @Test
    void noTaskListConfig_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(null));

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty(),
                "an absent policy is not a permissive policy");
    }

    @Test
    void featureDisabled_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenReturn(groupWith(new GroupTaskConfig()));

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty(),
                "the default GroupTaskConfig is off, and off must mean absent");
    }

    @Test
    void unreadableGroup_contributesNothing() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("mongo is down"));

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty(),
                "\"could not read the policy\" is not \"there is no policy\"");
    }

    @Test
    void missingGroup_contributesNothing() throws Exception {
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(null);

        assertTrue(provider().contribute(ctx(GC_ID)).specs().isEmpty());
        verify(groupStore, never()).read(anyString(), anyInt());
    }

    @Test
    void missingDependencies_contributeNothing() {
        // Field-injected on AgentOrchestrator, so a directly-constructed orchestrator
        // (which ~34 test classes build) leaves both null.
        assertTrue(new GroupTaskToolsProvider(null, groupStore).contribute(ctx(GC_ID)).specs().isEmpty());
        assertTrue(new GroupTaskToolsProvider(registry, null).contribute(ctx(GC_ID)).specs().isEmpty());
    }

    @Test
    void source_isBuiltin() {
        assertEquals("builtin", provider().source());
    }
}
