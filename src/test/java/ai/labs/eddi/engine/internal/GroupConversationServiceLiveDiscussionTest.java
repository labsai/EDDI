/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Proves {@code GroupConversationService#executeDiscussion} actually wires
 * {@link LiveDiscussionRegistry} the way F1 requires (Wave 0): {@code register}
 * at the top of the leg, {@code unregister} unconditionally in the
 * {@code finally} block, in that order, for every exit path. Unit coverage for
 * the registry class itself (identity semantics, replace-on-same-id, isolation)
 * lives in {@code LiveDiscussionRegistryTest}; this class is only about whether
 * the facade calls it correctly.
 * <p>
 * Drives {@code executeDiscussion} with an empty phase list so the run falls
 * straight through to the post-loop completion path without needing to mock the
 * phase-execution machinery — {@code conversationStore} is a plain mock, so
 * {@code updateIfState} returns Mockito's default and the completion path falls
 * through cleanly to {@code return gc;}.
 *
 * @author tests
 */
class GroupConversationServiceLiveDiscussionTest {

    private static final int MAX_DEPTH = 3;
    private static final String DEFAULT_TENANT = "default";

    @Mock
    private IAgentGroupStore groupStore;
    @Mock
    private IGroupConversationStore conversationStore;
    @Mock
    private IConversationService conversationService;
    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private NonceCacheService nonceCacheService;

    private GroupConversationService service;
    private LiveDiscussionRegistry registry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                mock(IScheduleStore.class), nonceCacheService, null, new CallerIdentityContext(null, null),
                DEFAULT_TENANT, MAX_DEPTH);
        registry = spy(new LiveDiscussionRegistry());
        service.liveDiscussionRegistry = registry;
    }

    private GroupConversation gc(String id) {
        var gc = new GroupConversation();
        gc.setId(id);
        gc.setGroupId("group-1");
        return gc;
    }

    @Test
    void executeDiscussion_normalCompletion_registersThenUnregisters() throws Exception {
        var gc = gc("gc-1");
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of());

        service.executeDiscussion(gc, config, List.of(), "Q?", null, 0);

        InOrder order = inOrder(registry);
        order.verify(registry).register(gc);
        order.verify(registry).unregister("gc-1");
        assertTrue(registry.get("gc-1").isEmpty(), "must not remain registered once the leg has finished");
    }

    @Test
    void executeDiscussion_throws_stillUnregisters() throws Exception {
        var gc = gc("gc-2");
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of());
        // Force the completion path to throw so the run exits through the catch
        // blocks instead of the happy path — the finally block must still fire.
        doThrow(new RuntimeException("store down")).when(conversationStore).updateIfState(any(), any());

        assertThrows(IGroupConversationService.GroupExecutionException.class,
                () -> service.executeDiscussion(gc, config, List.of(), "Q?", null, 0));

        verify(registry).register(gc);
        verify(registry).unregister("gc-2");
        assertTrue(registry.get("gc-2").isEmpty());
    }

    @Test
    void executeDiscussion_nullRegistry_isANoOpNotAFailure() {
        service.liveDiscussionRegistry = null;
        var gc = gc("gc-3");
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of());

        assertDoesNotThrow(() -> service.executeDiscussion(gc, config, List.of(), "Q?", null, 0));
    }
}
