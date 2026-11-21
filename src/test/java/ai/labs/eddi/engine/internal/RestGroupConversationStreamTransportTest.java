/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IRestGroupConversation.DiscussRequest;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B11 (last item): the group discussion stream had no keep-alive, no disconnect
 * handling and no backstop. It now runs on the same transport as the agent
 * stream ({@link SseStreams}).
 */
@DisplayName("Group discussion stream on the shared SSE transport")
class RestGroupConversationStreamTransportTest {

    private IGroupConversationService groupService;
    private RestGroupConversation rest;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<Runnable> ticks = new ArrayList<>();
    private final FakeSse sse = new FakeSse();

    @BeforeEach
    void setUp() {
        groupService = mock(IGroupConversationService.class);
        var identity = mock(SecurityIdentity.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        when(ownershipValidator.validateAndResolveUserId(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, mock(IConversationDescriptorStore.class),
                mock(IConversationService.class), groupService);
        var json = mock(IJsonSerialization.class);
        try {
            when(json.serialize(any())).thenReturn("{}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        rest = new RestGroupConversation(groupService, json, identity, ownershipValidator, hitlAccessGuard, mock(ResourceAccessGuard.class),
                new CallerIdentityContext(null, null));
        rest.sseStreams = SseStreams.unmetered();
        rest.sseStreams.clock = now::get;
        rest.sseStreams.clientGoneExecutor = Runnable::run;
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doAnswer(invocation -> {
            ticks.add(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        }).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
        rest.sseStreams.scheduler = scheduler;
    }

    @AfterEach
    void tearDown() {
        rest.shutdown();
    }

    private GroupDiscussionEventListener start(FakeSse.Sink sink) throws Exception {
        rest.discussStreaming("group-1", new DiscussRequest("Hello", "user-1"), sink, sse);
        var listener = ArgumentCaptor.forClass(GroupDiscussionEventListener.class);
        verify(groupService).startAndDiscussAsync(anyString(), anyString(), anyString(), listener.capture());
        return listener.getValue();
    }

    @Test
    @DisplayName("a silent discussion stream gets keep-alive comments")
    void keepAlive() throws Exception {
        var sink = new FakeSse.Sink(false);
        start(sink);

        now.addAndGet(rest.keepAliveIntervalSeconds * 1000L);
        ticks.forEach(Runnable::run);

        assertEquals(1, sink.comments());
    }

    @Test
    @DisplayName("a client that leaves releases the stream at once and does not cancel the discussion")
    void disconnectReleasesWithoutCancelling() throws Exception {
        var sink = new FakeSse.Sink(false);
        var listener = start(sink);
        listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent("gc-1", "group-1", "q", "round-robin", 1, List.of("a")));
        sink.close();
        listener.onSpeakerStart(new GroupConversationEventSink.SpeakerStartEvent("a", "A", 1, "Discussion"));

        assertEquals(0, rest.sseStreams.totalActive());
        verify(groupService, never()).cancelDiscussion(anyString(), any());
    }

    @Test
    @DisplayName("a completed discussion releases its stream")
    void completionReleases() throws Exception {
        var sink = new FakeSse.Sink(false);
        var listener = start(sink);
        listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(GroupConversation.GroupConversationState.COMPLETED, "answer"));

        assertTrue(sink.isClosed());
        assertEquals(0, rest.sseStreams.totalActive());
    }

    @Test
    @DisplayName("a member turn silent for far longer than the agent timeout does not end the stream (M1)")
    void longSilentMemberTurnKeepsTheStream() throws Exception {
        var sink = new FakeSse.Sink(false);
        var listener = start(sink);
        listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent("gc-1", "group-1", "q", "round-robin", 1, List.of("a")));
        listener.onSpeakerStart(new GroupConversationEventSink.SpeakerStartEvent("a", "A", 1, "Discussion"));

        // Thirty minutes of silence: a slow member, a nested group, a human member.
        for (int i = 0; i < 30 * 60 / Math.max(1, rest.keepAliveIntervalSeconds); i++) {
            now.addAndGet(rest.keepAliveIntervalSeconds * 1000L);
            ticks.forEach(Runnable::run);
        }

        assertEquals(0, sink.count(GroupConversationEventSink.EVENT_GROUP_ERROR));
        assertTrue(sink.comments() > 0, "the silence is filled with keep-alive comments");
        assertEquals(1, rest.sseStreams.totalActive());

        listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(GroupConversation.GroupConversationState.COMPLETED, "answer"));
        assertEquals(1, sink.count(GroupConversationEventSink.EVENT_GROUP_COMPLETE));
        assertTrue(sink.isClosed());
        assertEquals(0, rest.sseStreams.totalActive());
    }
}
