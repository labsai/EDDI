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
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * H9b: a GDPR erasure must stop the user's running group discussions before it
 * deletes their documents — a running discussion wrote its document back on its
 * next phase.
 */
class GroupConversationServiceErasureTest {

    private IGroupConversationStore conversationStore;
    private GroupConversationService service;
    private ConcurrentHashMap<String, DiscussionControlToken> activeTokens;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        conversationStore = mock(IGroupConversationStore.class);
        service = new GroupConversationService(mock(IAgentGroupStore.class), conversationStore, mock(IConversationService.class),
                mock(IAgentFactory.class), mock(ITemplatingEngine.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                mock(AgentSigningService.class), mock(IAgentStore.class), mock(IScheduleStore.class), mock(NonceCacheService.class), null,
                new CallerIdentityContext(null, null), "default", 3);
        var field = GroupConversationService.class.getDeclaredField("discussionControls");
        field.setAccessible(true);
        activeTokens = (ConcurrentHashMap<String, DiscussionControlToken>) field.get(service);
    }

    private void running(String id, String userId) throws Exception {
        var gc = new GroupConversation();
        gc.setId(id);
        gc.setUserId(userId);
        gc.setState(GroupConversationState.IN_PROGRESS);
        when(conversationStore.read(id)).thenReturn(gc);
        activeTokens.put(id, new DiscussionControlToken());
    }

    @Test
    void stopInFlightWork_cancelsOnlyTheErasedUsersRunningDiscussions() throws Exception {
        running("gc-erased-1", "erased-user");
        running("gc-erased-2", "erased-user");
        running("gc-other", "someone-else");

        assertEquals(2, service.stopInFlightWork("erased-user"));

        assertEquals(ControlSignal.CANCEL_IMMEDIATE, activeTokens.get("gc-erased-1").getSignal());
        assertEquals(ControlSignal.CANCEL_IMMEDIATE, activeTokens.get("gc-erased-2").getSignal());
        assertFalse(activeTokens.get("gc-other").isCancelled(), "another user's discussion must keep running");
    }

    @Test
    void stopInFlightWork_skipsADiscussionThatFinishedMeanwhile() throws Exception {
        activeTokens.put("gc-gone", new DiscussionControlToken());
        when(conversationStore.read("gc-gone")).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        assertEquals(0, service.stopInFlightWork("erased-user"));
    }

    /**
     * Review m1: one unreadable discussion must not leave the rest of the user's
     * discussions running; the failure is reported once, after the sweep.
     */
    @Test
    void stopInFlightWork_keepsSignallingPastAReadFailureAndReportsItAfterwards() throws Exception {
        activeTokens.put("gc-broken", new DiscussionControlToken());
        when(conversationStore.read("gc-broken")).thenThrow(new IResourceStore.ResourceStoreException("db down"));
        running("gc-erased", "erased-user");

        assertThrows(IllegalStateException.class, () -> service.stopInFlightWork("erased-user"));

        assertEquals(ControlSignal.CANCEL_IMMEDIATE, activeTokens.get("gc-erased").getSignal(),
                "the readable discussion must be cancelled even though another read failed");
    }

    @Test
    void stopInFlightWork_ignoresANullUser() {
        assertEquals(0, service.stopInFlightWork(null));
        verifyNoInteractions(conversationStore);
    }
}
