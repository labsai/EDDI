/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IRestGroupConversation.DiscussRequest;
import ai.labs.eddi.engine.api.IRestGroupConversation.FollowUpRequest;
import ai.labs.eddi.engine.groups.budget.TeamBudgetRefusedException;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.GroupErrorEvent;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A spent team budget reaches the caller as what docs/team-budgets.md promises:
 * 409 with the refusal's own text on discuss, continue and follow-up, and that
 * text in the {@code group_error} event of the streaming start. Before this,
 * the refusal fell through to a 500 (discuss), was replaced by the parent
 * type's canned "must be COMPLETED" text (continue, follow-up), or by a generic
 * stream error.
 */
class RestGroupConversationTeamBudgetTest {

    private static final String REFUSAL = "Team 'Content Studio' has spent $35.02 of its $35.00 budget for 2026-11; "
            + "it resets 2026-12-01T00:00:00Z.";

    private IGroupConversationService groupService;
    private IJsonSerialization jsonSerialization;
    private RestGroupConversation rest;

    @BeforeEach
    void setUp() {
        groupService = mock(IGroupConversationService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        var identity = mock(SecurityIdentity.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        when(ownershipValidator.validateAndResolveUserId(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, mock(IConversationDescriptorStore.class),
                mock(IConversationService.class), groupService);
        rest = new RestGroupConversation(groupService, jsonSerialization, identity, ownershipValidator, hitlAccessGuard,
                mock(ResourceAccessGuard.class), new CallerIdentityContext(null, null));
    }

    private static TeamBudgetRefusedException refusal() {
        return new TeamBudgetRefusedException(REFUSAL, "exhausted", null);
    }

    private GroupConversation completedInGroup() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setUserId("user-1");
        gc.setState(GroupConversation.GroupConversationState.COMPLETED);
        return gc;
    }

    @Test
    void discuss_spentBudget_is409WithTheReason() throws Exception {
        when(groupService.discuss("group-1", "Q", "user-1", 0)).thenThrow(refusal());

        Response response = rest.discuss("group-1", new DiscussRequest("Q", "user-1"));

        assertEquals(409, response.getStatus());
        assertEquals(REFUSAL, response.getEntity());
    }

    @Test
    void continue_spentBudget_is409WithTheReason() throws Exception {
        when(groupService.readGroupConversation("gc-1")).thenReturn(completedInGroup());
        when(groupService.continueDiscussion("gc-1", "Q", null)).thenThrow(refusal());

        Response response = rest.continueDiscussion("group-1", "gc-1", new DiscussRequest("Q", "user-1"));

        assertEquals(409, response.getStatus());
        assertEquals(REFUSAL, response.getEntity());
    }

    @Test
    void followUp_spentBudget_is409WithTheReason() throws Exception {
        when(groupService.readGroupConversation("gc-1")).thenReturn(completedInGroup());
        when(groupService.followUpWithMember("gc-1", "agentA", "Q")).thenThrow(refusal());

        Response response = rest.followUpWithMember("group-1", "gc-1", new FollowUpRequest("Q", "agentA", "user-1"));

        assertEquals(409, response.getStatus());
        assertEquals(REFUSAL, response.getEntity());
    }

    @Test
    void streamingStart_spentBudget_sendsTheReasonInTheErrorEvent() throws Exception {
        when(groupService.startAndDiscussAsync(any(), any(), any(), any())).thenThrow(refusal());
        var sink = mock(SseEventSink.class);
        var sse = mock(Sse.class, RETURNS_DEEP_STUBS);

        rest.discussStreaming("group-1", new DiscussRequest("Q", "user-1"), sink, sse);

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(jsonSerialization, atLeastOnce()).serialize(payload.capture());
        assertEquals(new GroupErrorEvent(REFUSAL), payload.getValue());
        verify(sink).close();
    }
}
