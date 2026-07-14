/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IRestGroupConversation.DiscussRequest;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link RestGroupConversation} — SSE streaming paths, event
 * listener callbacks, and error handling in the streaming endpoint.
 */
class RestGroupConversationExtendedTest {

    private IGroupConversationService groupService;
    private IJsonSerialization jsonSerialization;
    private SecurityIdentity identity;
    private OwnershipValidator ownershipValidator;
    private RestGroupConversation restGroupConversation;
    private SseEventSink eventSink;
    private Sse sse;

    @BeforeEach
    void setUp() {
        groupService = mock(IGroupConversationService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        identity = mock(SecurityIdentity.class);
        ownershipValidator = mock(OwnershipValidator.class);
        when(ownershipValidator.validateAndResolveUserId(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        var hitlAccessGuard = new ai.labs.eddi.engine.hitl.HitlAccessGuard(
                identity, ownershipValidator,
                mock(ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore.class),
                mock(ai.labs.eddi.engine.api.IConversationService.class),
                groupService);
        restGroupConversation = new RestGroupConversation(
                groupService, jsonSerialization, identity, ownershipValidator, hitlAccessGuard);
        eventSink = mock(SseEventSink.class);
        sse = mock(Sse.class);

        // Default: sink is open
        when(eventSink.isClosed()).thenReturn(false);

        // Mock the SSE event builder chain:
        // sse.newEventBuilder().name(x).data(y).build()
        var eventBuilder = mock(OutboundSseEvent.Builder.class);
        var sseEvent = mock(OutboundSseEvent.class);
        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(sseEvent);
    }

    @AfterEach
    void tearDown() {
        // Each test builds a fresh RestGroupConversation whose virtual-thread executor
        // is otherwise only closed by the CDI @PreDestroy — never invoked in a plain
        // unit test. Shut it down here so a full suite run does not accumulate
        // un-terminated executors.
        restGroupConversation.shutdown();
    }

    // Helper factory methods for events matching actual record signatures
    private GroupConversationEventSink.GroupStartEvent groupStartEvent() {
        return new GroupConversationEventSink.GroupStartEvent(
                "gc-1", "g-1", "What is AI?", "round-robin", 1, List.of("agent-1"));
    }

    private GroupConversationEventSink.PhaseStartEvent phaseStartEvent() {
        return new GroupConversationEventSink.PhaseStartEvent(1, "Discussion", "discussion", "agent-1, agent-2");
    }

    private GroupConversationEventSink.SpeakerStartEvent speakerStartEvent() {
        return new GroupConversationEventSink.SpeakerStartEvent("agent-1", "Agent One", 1, "Discussion");
    }

    private GroupConversationEventSink.SpeakerCompleteEvent speakerCompleteEvent() {
        return new GroupConversationEventSink.SpeakerCompleteEvent("agent-1", "Agent One", "The answer is 42", 1, "Discussion");
    }

    private GroupConversationEventSink.PhaseCompleteEvent phaseCompleteEvent() {
        return new GroupConversationEventSink.PhaseCompleteEvent(1, "Discussion");
    }

    private GroupConversationEventSink.SynthesisStartEvent synthesisStartEvent() {
        return new GroupConversationEventSink.SynthesisStartEvent("moderator-1");
    }

    private GroupConversationEventSink.GroupCompleteEvent groupCompleteEvent() {
        return new GroupConversationEventSink.GroupCompleteEvent(
                GroupConversation.GroupConversationState.COMPLETED, "Final synthesis");
    }

    private GroupConversationEventSink.GroupErrorEvent groupErrorEvent() {
        return new GroupConversationEventSink.GroupErrorEvent("Something broke");
    }

    // ─── SSE streaming endpoint ───────────────────────────────

    @Nested
    @DisplayName("discussStreaming")
    class DiscussStreaming {

        @Test
        @DisplayName("should capture listener and invoke startAndDiscussAsync")
        void capturesListenerAndDelegates() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            restGroupConversation.discussStreaming("group-1",
                    new DiscussRequest("Hello", "user-1"), eventSink, sse);

            verify(groupService).startAndDiscussAsync(eq("group-1"), eq("Hello"),
                    eq("user-1"), any(GroupDiscussionEventListener.class));
        }

        @Test
        @DisplayName("anonymous user defaults to 'anonymous'")
        void anonymousUserInStreaming() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            restGroupConversation.discussStreaming("group-1",
                    new DiscussRequest("Hello", null), eventSink, sse);

            verify(groupService).startAndDiscussAsync(eq("group-1"), eq("Hello"),
                    eq("anonymous"), any(GroupDiscussionEventListener.class));
        }

        @Test
        @DisplayName("ResourceNotFoundException sends error event and closes")
        void resourceNotFoundSendsError() throws Exception {
            doThrow(new IResourceStore.ResourceNotFoundException("Group not found"))
                    .when(groupService).startAndDiscussAsync(anyString(), anyString(),
                            anyString(), any());
            when(jsonSerialization.serialize(any())).thenReturn("{\"error\":\"Group not found\"}");

            restGroupConversation.discussStreaming("nonexistent",
                    new DiscussRequest("Q", "u"), eventSink, sse);

            verify(eventSink).send(any(OutboundSseEvent.class));
            verify(eventSink).close();
        }

        @Test
        @DisplayName("general exception sends error event and closes")
        void generalExceptionSendsError() throws Exception {
            doThrow(new RuntimeException("Unexpected error"))
                    .when(groupService).startAndDiscussAsync(anyString(), anyString(),
                            anyString(), any());
            when(jsonSerialization.serialize(any())).thenReturn("{\"error\":\"Unexpected\"}");

            restGroupConversation.discussStreaming("group-1",
                    new DiscussRequest("Q", "u"), eventSink, sse);

            verify(eventSink).send(any(OutboundSseEvent.class));
            verify(eventSink).close();
        }
    }

    // ─── SSE listener callbacks ─────────────────────────────

    @Nested
    @DisplayName("GroupDiscussionEventListener callbacks")
    class ListenerCallbacks {

        private GroupDiscussionEventListener capturedListener;

        @BeforeEach
        void captureListener() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            restGroupConversation.discussStreaming("group-1",
                    new DiscussRequest("Hello", "user-1"), eventSink, sse);

            var captor = ArgumentCaptor.forClass(GroupDiscussionEventListener.class);
            verify(groupService).startAndDiscussAsync(anyString(), anyString(),
                    anyString(), captor.capture());
            capturedListener = captor.getValue();
        }

        @Test
        @DisplayName("onGroupStart sends group_start event")
        void onGroupStart() {
            capturedListener.onGroupStart(groupStartEvent());
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("onPhaseStart sends phase_start event")
        void onPhaseStart() {
            capturedListener.onPhaseStart(phaseStartEvent());
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("onSpeakerStart sends speaker_start event")
        void onSpeakerStart() {
            capturedListener.onSpeakerStart(speakerStartEvent());
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("onSpeakerComplete sends speaker_complete event")
        void onSpeakerComplete() {
            capturedListener.onSpeakerComplete(speakerCompleteEvent());
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("onPhaseComplete sends phase_complete event")
        void onPhaseComplete() {
            capturedListener.onPhaseComplete(phaseCompleteEvent());
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("onSynthesisStart sends synthesis_start event")
        void onSynthesisStart() {
            capturedListener.onSynthesisStart(synthesisStartEvent());
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("onGroupComplete sends group_complete event and closes sink")
        void onGroupComplete() {
            capturedListener.onGroupComplete(groupCompleteEvent());
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
            verify(eventSink).close();
        }

        @Test
        @DisplayName("onGroupError sends group_error event and closes sink")
        void onGroupError() {
            capturedListener.onGroupError(groupErrorEvent());
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
            verify(eventSink).close();
        }
    }

    // ─── SSE helper edge cases ──────────────────────────────

    @Nested
    @DisplayName("SSE helper methods")
    class SseHelpers {

        @Test
        @DisplayName("sendEvent drops event when sink is closed")
        void dropsEventWhenClosed() throws Exception {
            when(eventSink.isClosed()).thenReturn(true);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            restGroupConversation.discussStreaming("group-1",
                    new DiscussRequest("Hello", "user-1"), eventSink, sse);

            var captor = ArgumentCaptor.forClass(GroupDiscussionEventListener.class);
            verify(groupService).startAndDiscussAsync(anyString(), anyString(),
                    anyString(), captor.capture());
            var listener = captor.getValue();

            // Reset to verify no sends after this point
            reset(eventSink);
            when(eventSink.isClosed()).thenReturn(true);

            listener.onGroupStart(groupStartEvent());

            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("sendEvent handles send exception gracefully")
        void handlesSendException() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            when(eventSink.send(any(OutboundSseEvent.class))).thenThrow(new RuntimeException("Connection lost"));

            restGroupConversation.discussStreaming("group-1",
                    new DiscussRequest("Hello", "user-1"), eventSink, sse);

            var captor = ArgumentCaptor.forClass(GroupDiscussionEventListener.class);
            verify(groupService).startAndDiscussAsync(anyString(), anyString(),
                    anyString(), captor.capture());
            var listener = captor.getValue();

            assertDoesNotThrow(() -> listener.onGroupStart(groupStartEvent()));
        }

        @Test
        @DisplayName("closeQuietly handles exception when closing")
        void closeQuietlyHandlesException() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            doThrow(new RuntimeException("Close failed")).when(eventSink).close();

            restGroupConversation.discussStreaming("group-1",
                    new DiscussRequest("Hello", "user-1"), eventSink, sse);

            var captor = ArgumentCaptor.forClass(GroupDiscussionEventListener.class);
            verify(groupService).startAndDiscussAsync(anyString(), anyString(),
                    anyString(), captor.capture());
            var listener = captor.getValue();

            assertDoesNotThrow(() -> listener.onGroupComplete(groupCompleteEvent()));
        }

        @Test
        @DisplayName("toJson returns '{}' when serialization fails")
        void toJsonFallback() throws Exception {
            when(jsonSerialization.serialize(any())).thenThrow(new RuntimeException("Serialization failed"));

            restGroupConversation.discussStreaming("group-1",
                    new DiscussRequest("Hello", "user-1"), eventSink, sse);

            var captor = ArgumentCaptor.forClass(GroupDiscussionEventListener.class);
            verify(groupService).startAndDiscussAsync(anyString(), anyString(),
                    anyString(), captor.capture());
            var listener = captor.getValue();

            assertDoesNotThrow(() -> listener.onGroupStart(groupStartEvent()));
        }

        @Test
        @DisplayName("closeQuietly is no-op when already closed")
        void closeQuietlyNoOpWhenAlreadyClosed() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            restGroupConversation.discussStreaming("group-1",
                    new DiscussRequest("Hello", "user-1"), eventSink, sse);

            var captor = ArgumentCaptor.forClass(GroupDiscussionEventListener.class);
            verify(groupService).startAndDiscussAsync(anyString(), anyString(),
                    anyString(), captor.capture());
            var listener = captor.getValue();

            when(eventSink.isClosed()).thenReturn(true);

            listener.onGroupComplete(groupCompleteEvent());

            verify(eventSink, never()).close();
        }
    }

    // ─── continue/stream endpoint ───────────────────────────
    @Nested
    @DisplayName("continueDiscussionStreaming")
    class ContinueDiscussionStreaming {

        private GroupConversation gcInGroup(String groupId) {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setGroupId(groupId);
            gc.setUserId("user-1");
            return gc;
        }

        @Test
        @DisplayName("group mismatch sends group_error, closes, and does not delegate")
        void groupMismatch_sendsErrorAndCloses() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("other-group"));
            when(jsonSerialization.serialize(any())).thenReturn("{\"error\":\"not found\"}");

            restGroupConversation.continueDiscussionStreaming("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"), eventSink, sse);

            verify(eventSink).send(any(OutboundSseEvent.class));
            verify(eventSink).close();
            verify(groupService, never()).continueDiscussion(any(), any(), any());
        }

        @Test
        @DisplayName("ForbiddenException is rethrown (maps to 403), not converted to an SSE error")
        void forbidden_isRethrown() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            doThrow(new ForbiddenException("denied"))
                    .when(ownershipValidator).requireOwnerOrAdmin(any(), eq("user-1"), anyString());

            assertThrows(ForbiddenException.class,
                    () -> restGroupConversation.continueDiscussionStreaming("group-1", "gc-1",
                            new DiscussRequest("q", "user-1"), eventSink, sse));

            verify(eventSink, never()).send(any(OutboundSseEvent.class));
            verify(eventSink, never()).close();
            verify(groupService, never()).continueDiscussion(any(), any(), any(), any());
        }

        @Test
        @DisplayName("happy path delegates continue to the background executor")
        void success_delegatesToExecutor() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.continueDiscussion(eq("gc-1"), eq("q"), any(), any())).thenReturn(gcInGroup("group-1"));
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            restGroupConversation.continueDiscussionStreaming("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"), eventSink, sse);

            // continue runs on a background virtual thread — wait for the invocation
            verify(groupService, timeout(2000)).continueDiscussion(eq("gc-1"), eq("q"), any(), any());
        }

        @Test
        @DisplayName("executor task failure routes the error to the SSE sink")
        void executorTaskFailure_sendsErrorAndCloses() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.continueDiscussion(eq("gc-1"), eq("q"), any(), any()))
                    .thenThrow(new IGroupConversationService.GroupDiscussionException("boom"));
            when(jsonSerialization.serialize(any())).thenReturn("{\"error\":\"boom\"}");

            restGroupConversation.continueDiscussionStreaming("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"), eventSink, sse);

            // onGroupError runs on the background thread — wait for the close
            verify(eventSink, timeout(2000)).close();
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("continuation streams HITL pause + cancel events (shared listener) instead of hanging the client")
        void continueListenerForwardsHitlAndCancelEvents() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.continueDiscussion(eq("gc-1"), eq("q"), any(), any())).thenReturn(gcInGroup("group-1"));
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            restGroupConversation.continueDiscussionStreaming("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"), eventSink, sse);

            var captor = ArgumentCaptor.forClass(GroupDiscussionEventListener.class);
            verify(groupService, timeout(2000)).continueDiscussion(eq("gc-1"), eq("q"), captor.capture(), any());
            var listener = captor.getValue();

            // The old hand-rolled inline listener had no HITL overrides, so a continuation
            // that paused for approval emitted NOTHING and the SSE client hung forever.
            listener.onHitlPause(new GroupConversationEventSink.HitlPauseEvent(0, "Debate", "needs approval", "PHASE"));

            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
            verify(eventSink).close();
        }

        @Test
        @DisplayName("continuation streams round_start (the shared listener keeps ours' event)")
        void continueListenerForwardsRoundStart() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.continueDiscussion(eq("gc-1"), eq("q"), any(), any())).thenReturn(gcInGroup("group-1"));
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            restGroupConversation.continueDiscussionStreaming("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"), eventSink, sse);

            var captor = ArgumentCaptor.forClass(GroupDiscussionEventListener.class);
            verify(groupService, timeout(2000)).continueDiscussion(eq("gc-1"), eq("q"), captor.capture(), any());

            captor.getValue().onRoundStart(
                    new GroupConversationEventSink.RoundStartEvent("gc-1", 2, "q", 3));

            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
        }
    }
}
