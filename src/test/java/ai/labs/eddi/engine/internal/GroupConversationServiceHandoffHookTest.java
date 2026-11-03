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
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.internal.handoff.DecisionHandoffs;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pins the one hook handoff has in {@link GroupConversationService}: the
 * {@code onDecision} rules run at successful completion, after the persisting
 * compare-and-set, before the listener hears {@code group_complete}.
 */
class GroupConversationServiceHandoffHookTest {

    private IGroupConversationStore conversationStore;
    private GroupConversationService service;
    private DecisionHandoffs decisionHandoffs;

    @BeforeEach
    void setUp() {
        conversationStore = mock(IGroupConversationStore.class);
        service = new GroupConversationService(mock(IAgentGroupStore.class), conversationStore, mock(IConversationService.class),
                mock(IAgentFactory.class), mock(ITemplatingEngine.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                mock(AgentSigningService.class), mock(IAgentStore.class), mock(IScheduleStore.class), mock(NonceCacheService.class), null,
                new CallerIdentityContext(null, null), "default", 3);
        decisionHandoffs = mock(DecisionHandoffs.class);
        service.decisionHandoffs = decisionHandoffs;
    }

    private GroupConversation running() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setUserId("user-1");
        gc.setState(GroupConversationState.IN_PROGRESS);
        return gc;
    }

    @Test
    void completionFilesTheDecisionRulesBeforeAnnouncingGroupComplete() throws Exception {
        var gc = running();
        var config = new AgentGroupConfiguration();
        var listener = mock(GroupDiscussionEventListener.class);

        var result = service.executeDiscussion(gc, config, List.of(), "question", listener, 0);

        assertEquals(GroupConversationState.COMPLETED, result.getState());
        InOrder order = inOrder(conversationStore, decisionHandoffs, listener);
        order.verify(conversationStore).updateIfState(any(), any());
        order.verify(decisionHandoffs).afterCompleted(eq(gc), eq(config), eq(listener));
        order.verify(listener).onGroupComplete(any());
    }

    @Test
    void aDiscussionThatEndsAnyOtherWayFilesNothing() throws Exception {
        var gc = running();
        doThrow(new IllegalStateException("store down")).when(conversationStore).updateIfState(any(), any());

        try {
            service.executeDiscussion(gc, new AgentGroupConfiguration(), List.of(), "question", null, 0);
        } catch (Exception e) {
            // the failure path itself is not under test here
        }

        verify(decisionHandoffs, never()).afterCompleted(any(), any(), any());
    }
}
