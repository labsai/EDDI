/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.PendingHumanInput;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a group conversation's approvers may see and do beyond deciding: the
 * pending human prompt, the resumed stream, and the follow-up that runs a
 * member.
 */
class RestGroupConversationApproverScopeTest {

    private static final String GROUP_ID = "group-1";
    private static final String GC_ID = "gc-scope-1";
    private static final String OWNER_ID = "owner1";
    private static final String APPROVER_ID = "approver1";
    private static final String MEMBER_ID = "member1";
    private static final String PROMPT = "Peer said: the Q3 numbers are confidential. Your turn.";

    private IGroupConversationService groupService;
    private SecurityIdentity identity;
    private ResourceAccessGuard resourceAccessGuard;
    private RestGroupConversation rest;
    private final List<String> sentEvents = new ArrayList<>();
    private final List<Object> serialized = new ArrayList<>();
    private SseEventSink eventSink;
    private Sse sse;

    @BeforeEach
    void setUp() throws Exception {
        groupService = mock(IGroupConversationService.class);
        identity = mock(SecurityIdentity.class);
        resourceAccessGuard = mock(ResourceAccessGuard.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        when(jsonSerialization.serialize(any())).thenAnswer(inv -> {
            serialized.add(inv.getArgument(0));
            return "{}";
        });
        var ownershipValidator = new OwnershipValidator(true);
        when(identity.isAnonymous()).thenReturn(false);
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, mock(IConversationDescriptorStore.class),
                mock(IConversationService.class), groupService);
        rest = new RestGroupConversation(groupService, jsonSerialization, identity, ownershipValidator, hitlAccessGuard,
                resourceAccessGuard);

        eventSink = mock(SseEventSink.class);
        sse = mock(Sse.class);
        var builder = mock(OutboundSseEvent.Builder.class);
        when(sse.newEventBuilder()).thenReturn(builder);
        when(builder.name(anyString())).thenAnswer(inv -> {
            sentEvents.add(inv.getArgument(0));
            return builder;
        });
        when(builder.data(any(Class.class), any())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(OutboundSseEvent.class));
    }

    private void as(String userId, String... roles) {
        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn(userId);
        when(identity.getPrincipal()).thenReturn(principal);
        for (String role : roles) {
            when(identity.hasRole(role)).thenReturn(true);
        }
    }

    private GroupConversation gc(GroupConversationState state) throws Exception {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId(OWNER_ID);
        gc.setState(state);
        gc.setPausedAt(Instant.parse("2026-10-26T09:00:00Z"));
        when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
        return gc;
    }

    @Nested
    @DisplayName("#8 pendingHumanPrompt in the approval-status summary")
    class PendingHumanPrompt {

        @SuppressWarnings("unchecked")
        private Map<String, Object> summary() {
            Response response = rest.getGroupApprovalStatus(GROUP_ID, GC_ID, null);
            assertEquals(200, response.getStatus());
            return (Map<String, Object>) response.getEntity();
        }

        private void humanTurnPending() throws Exception {
            var gc = gc(GroupConversationState.AWAITING_HUMAN_INPUT);
            gc.setPendingHumanInput(new PendingHumanInput(MEMBER_ID, "Member One", 0, 0, 0, "OPINION", PROMPT, "SKIP", Instant.now()));
        }

        @Test
        @DisplayName("an approver-only caller sees who is up, but not what they were asked")
        void approverGetsNoPrompt() throws Exception {
            humanTurnPending();
            as(APPROVER_ID, "eddi-approver");

            var summary = summary();

            assertEquals(MEMBER_ID, summary.get("pendingMemberId"));
            assertFalse(summary.containsKey("pendingHumanPrompt"), "the prompt is discussion content: " + summary);
        }

        @Test
        @DisplayName("the pending member gets their prompt")
        void memberGetsPrompt() throws Exception {
            humanTurnPending();
            as(MEMBER_ID);

            assertEquals(PROMPT, summary().get("pendingHumanPrompt"));
        }

        @Test
        @DisplayName("the owner gets the prompt")
        void ownerGetsPrompt() throws Exception {
            humanTurnPending();
            as(OWNER_ID);

            assertEquals(PROMPT, summary().get("pendingHumanPrompt"));
        }
    }

    @Nested
    @DisplayName("#27 the stream an approve/stream caller receives")
    class ResumedStream {

        private GroupApprovalRequest approval() {
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            var request = new GroupApprovalRequest();
            request.setDecision(decision);
            return request;
        }

        private boolean closed;

        /** Runs the approval and replays a resumed discussion into its listener. */
        private void approveAndResume() throws Exception {
            gc(GroupConversationState.AWAITING_APPROVAL);
            when(eventSink.isClosed()).thenAnswer(inv -> closed);
            doAnswer(inv -> {
                closed = true;
                return null;
            }).when(eventSink).close();
            when(groupService.resumeDiscussion(eq(GC_ID), any(), any())).thenAnswer(inv -> {
                GroupDiscussionEventListener l = inv.getArgument(2);
                l.onHitlResume(new GroupConversationEventSink.HitlResumeEvent("APPROVED", null, APPROVER_ID));
                l.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent("agent-a", "A", "private answer", 1, "debate",
                        null, null, "OK"));
                l.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(GroupConversationState.COMPLETED, "synthesis"));
                return null;
            });
            rest.approveGroupPhaseStreaming(GROUP_ID, GC_ID, approval(), eventSink, sse);
        }

        @Test
        @DisplayName("an approver-only caller gets the outcome of their decision, and the stream ends there")
        void approverStreamEndsAtResume() throws Exception {
            as(APPROVER_ID, "eddi-approver");

            approveAndResume();

            assertEquals(List.of(GroupConversationEventSink.EVENT_HITL_RESUME), sentEvents);
            assertTrue(serialized.stream().noneMatch(GroupConversationEventSink.SpeakerCompleteEvent.class::isInstance),
                    "no member response may reach an approver-only stream: " + serialized);
            verify(eventSink).close();
        }

        @Test
        @DisplayName("an approver-only caller whose resume could not be enqueued gets the error, not hitl_resume")
        void approverStreamReportsAFailedSubmit() throws Exception {
            as(APPROVER_ID, "eddi-approver");
            gc(GroupConversationState.AWAITING_APPROVAL);
            when(eventSink.isClosed()).thenAnswer(inv -> closed);
            doAnswer(inv -> {
                closed = true;
                return null;
            }).when(eventSink).close();
            // The coordinator announces the committed resume, then fails to submit the
            // resumed run, restores the pause and throws.
            when(groupService.resumeDiscussion(eq(GC_ID), any(), any())).thenAnswer(inv -> {
                GroupDiscussionEventListener l = inv.getArgument(2);
                l.onHitlResume(new GroupConversationEventSink.HitlResumeEvent("APPROVED", null, APPROVER_ID));
                throw new IResourceStore.ResourceStoreException("Failed to submit resumed group discussion: rejected");
            });

            rest.approveGroupPhaseStreaming(GROUP_ID, GC_ID, approval(), eventSink, sse);

            assertEquals(List.of(GroupConversationEventSink.EVENT_GROUP_ERROR), sentEvents);
            verify(eventSink).close();
        }

        @Test
        @DisplayName("the owner follows the resumed discussion as before")
        void ownerGetsTheFullStream() throws Exception {
            as(OWNER_ID);

            approveAndResume();

            assertTrue(sentEvents.contains(GroupConversationEventSink.EVENT_SPEAKER_COMPLETE), String.valueOf(sentEvents));
            assertTrue(sentEvents.contains(GroupConversationEventSink.EVENT_GROUP_COMPLETE), String.valueOf(sentEvents));
        }
    }

    @Nested
    @DisplayName("#37 a follow-up re-checks USE on the group")
    class FollowUp {

        @Test
        @DisplayName("an owner who lost access to the group cannot run a member through a follow-up")
        void followUpRequiresGroupUse() throws Exception {
            gc(GroupConversationState.COMPLETED);
            as(OWNER_ID);
            doThrow(new ForbiddenException("no USE")).when(resourceAccessGuard).requireUseAccess(GROUP_ID, "group");

            assertThrows(ForbiddenException.class,
                    () -> rest.followUpWithMember(GROUP_ID, GC_ID, new IRestGroupConversation.FollowUpRequest("q", "agent-1", null)));
            verify(groupService, never()).followUpWithMember(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("an owner who still may use the group gets the follow-up")
        void followUpRunsWithGroupUse() throws Exception {
            var gc = gc(GroupConversationState.COMPLETED);
            as(OWNER_ID);
            when(groupService.followUpWithMember(GC_ID, "agent-1", "q")).thenReturn(gc);

            Response response = rest.followUpWithMember(GROUP_ID, GC_ID, new IRestGroupConversation.FollowUpRequest("q", "agent-1", null));

            assertEquals(200, response.getStatus());
            verify(resourceAccessGuard).requireUseAccess(GROUP_ID, "group");
        }
    }
}
