/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * The SSE keep-alive of {@link RestAgentEngineStreaming}: a turn stream that
 * stays silent for the interval gets a {@code : keep-alive} comment line, so a
 * load balancer's idle timeout does not cut a turn whose model is still
 * thinking. The scheduler is replaced so each test runs the tick by hand.
 */
class RestAgentEngineStreamingKeepAliveTest {

    private static final int INTERVAL_SECONDS = 15;
    private static final long INTERVAL_MS = TimeUnit.SECONDS.toMillis(INTERVAL_SECONDS);

    private IConversationService conversationService;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    /**
     * Disconnect handling handed off by a tick, run by the test when it chooses.
     */
    private final List<Runnable> clientGone = new ArrayList<>();

    private SseEventSink eventSink;
    private Sse sse;
    private OutboundSseEvent.Builder eventBuilder;
    private OutboundSseEvent event;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        scheduler = mock(ScheduledExecutorService.class);
        future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());

        eventSink = mock(SseEventSink.class);
        sse = mock(Sse.class);
        eventBuilder = mock(OutboundSseEvent.Builder.class);
        event = mock(OutboundSseEvent.class);
        when(eventSink.isClosed()).thenReturn(false);
        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
        when(eventBuilder.comment(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(event);
    }

    private RestAgentEngineStreaming streaming(boolean cancelOnDisconnect, int intervalSeconds) {
        var streaming = new RestAgentEngineStreaming(conversationService, mock(ConversationAccessGuard.class),
                cancelOnDisconnect, intervalSeconds);
        streaming.shutdownKeepAlive();
        streaming.keepAliveScheduler = scheduler;
        streaming.clockMillis = now::get;
        streaming.clientGoneExecutor = clientGone::add;
        return streaming;
    }

    private IConversationService.StreamingResponseHandler startTurn(RestAgentEngineStreaming streaming) throws Exception {
        var input = new InputData();
        input.setInput("Hello");
        streaming.sayStreaming("conv-1", false, false, List.of(), input, eventSink, sse);
        var handler = ArgumentCaptor.forClass(IConversationService.StreamingResponseHandler.class);
        verify(conversationService).sayStreaming(eq("conv-1"), any(), any(), any(), any(), handler.capture());
        return handler.getValue();
    }

    private Runnable scheduledTick() {
        var tick = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(tick.capture(), eq(INTERVAL_MS / 4), eq(INTERVAL_MS / 4),
                eq(TimeUnit.MILLISECONDS));
        return tick.getValue();
    }

    @Test
    @DisplayName("a stream silent for the whole interval gets one keep-alive comment")
    void silentStreamGetsComment() throws Exception {
        startTurn(streaming(true, INTERVAL_SECONDS));
        Runnable tick = scheduledTick();

        now.addAndGet(INTERVAL_MS);
        tick.run();

        verify(eventBuilder).comment(RestAgentEngineStreaming.KEEPALIVE_COMMENT);
        verify(eventSink).send(event);
    }

    @Test
    @DisplayName("no comment while events are flowing: a recent event resets the silence")
    void recentEventSuppressesComment() throws Exception {
        var handler = startTurn(streaming(true, INTERVAL_SECONDS));
        Runnable tick = scheduledTick();

        now.addAndGet(INTERVAL_MS - 1);
        handler.onToken("Hel");
        now.addAndGet(INTERVAL_MS - 1);
        tick.run();

        verify(eventBuilder, never()).comment(anyString());
        verify(eventSink, times(1)).send(event);

        // A full interval after the token, the comment goes out.
        now.addAndGet(1);
        tick.run();
        verify(eventBuilder).comment(RestAgentEngineStreaming.KEEPALIVE_COMMENT);
    }

    @Test
    @DisplayName("the tick stops when the turn ends, and a late tick writes nothing")
    void stopsWhenTurnEnds() throws Exception {
        var handler = startTurn(streaming(true, INTERVAL_SECONDS));
        Runnable tick = scheduledTick();

        handler.onComplete(mock(SimpleConversationMemorySnapshot.class));
        verify(future).cancel(false);

        now.addAndGet(10 * INTERVAL_MS);
        tick.run();
        verify(eventBuilder, never()).comment(anyString());
    }

    @Test
    @DisplayName("a client that vanished during the silence is noticed by the tick and the turn is cancelled")
    void closedSinkDuringSilenceCancelsTurn() throws Exception {
        var handler = startTurn(streaming(true, INTERVAL_SECONDS));
        Runnable tick = scheduledTick();
        handler.onTaskStart(new TaskId("ai.labs.llm"), "llm", 0);

        when(eventSink.isClosed()).thenReturn(true);
        now.addAndGet(INTERVAL_MS);
        tick.run();

        // The tick only hands the cancel off: the store work must not run on the
        // one thread that ticks every stream.
        verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        assertEquals(1, clientGone.size());
        clientGone.forEach(Runnable::run);

        verify(conversationService).cancelConversation("conv-1", ControlSignal.CANCEL_GRACEFUL,
                RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
        verify(future, atLeastOnce()).cancel(false);
        verify(eventBuilder, never()).comment(anyString());
    }

    @Test
    @DisplayName("a stream still queued behind another turn is not cancelled by the tick; its own first event cancels it")
    void closedSinkBeforeTurnStartedDoesNotCancelOtherTurn() throws Exception {
        var handler = startTurn(streaming(true, INTERVAL_SECONDS));
        Runnable tick = scheduledTick();

        when(eventSink.isClosed()).thenReturn(true);
        now.addAndGet(INTERVAL_MS);
        tick.run();

        // No event of this turn was written yet: it may be queued, and a
        // conversation-wide cancel would hit the turn that is running.
        assertEquals(0, clientGone.size());
        verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        verify(future, atLeastOnce()).cancel(false);

        // When this turn starts, its first event finds the sink closed and cancels it.
        handler.onTaskStart(new TaskId("ai.labs.llm"), "llm", 0);
        verify(conversationService).cancelConversation("conv-1", ControlSignal.CANCEL_GRACEFUL,
                RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
    }

    @Test
    @DisplayName("with cancel-on-client-disconnect off, a vanished client stops the tick but not the turn")
    void closedSinkWithoutCancelKeepsTurn() throws Exception {
        var handler = startTurn(streaming(false, INTERVAL_SECONDS));
        Runnable tick = scheduledTick();
        handler.onTaskStart(new TaskId("ai.labs.llm"), "llm", 0);

        when(eventSink.isClosed()).thenReturn(true);
        now.addAndGet(INTERVAL_MS);
        tick.run();
        clientGone.forEach(Runnable::run);

        verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        verify(future, atLeastOnce()).cancel(false);
    }

    @Test
    @DisplayName("the interval is bound to eddi.streaming.keepalive-interval-seconds, default 15")
    void intervalIsBoundToAConfigProperty() throws Exception {
        var constructor = RestAgentEngineStreaming.class.getDeclaredConstructor(
                IConversationService.class, ConversationAccessGuard.class, boolean.class, int.class);

        String defaultValue = null;
        for (Annotation[] parameterAnnotations : constructor.getParameterAnnotations()) {
            for (Annotation annotation : parameterAnnotations) {
                if (annotation instanceof ConfigProperty configProperty
                        && "eddi.streaming.keepalive-interval-seconds".equals(configProperty.name())) {
                    defaultValue = configProperty.defaultValue();
                }
            }
        }

        assertEquals("15", defaultValue, "operators tune or disable the keep-alive through this property");
    }

    @Test
    @DisplayName("interval 0 turns the keep-alive off: nothing is scheduled")
    void zeroIntervalSchedulesNothing() throws Exception {
        startTurn(streaming(true, 0));

        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("a send that throws on an open sink is not taken for a disconnect")
    void sendFailureOnOpenSinkKeepsTurn() throws Exception {
        startTurn(streaming(true, INTERVAL_SECONDS));
        Runnable tick = scheduledTick();

        doThrow(new IllegalStateException("boom")).when(eventSink).send(any(OutboundSseEvent.class));
        now.addAndGet(INTERVAL_MS);
        tick.run();

        verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        verify(future, never()).cancel(anyBoolean());
    }
}
