/*
 * Copyright (c) 2016-2026 EDDI contributors
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
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
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
    private RestGroupConversation restGroupConversation;
    private SseEventSink eventSink;
    private Sse sse;

    @BeforeEach
    void setUp() {
        groupService = mock(IGroupConversationService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        restGroupConversation = new RestGroupConversation(groupService, jsonSerialization);
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
}
