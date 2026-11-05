/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.lifecycle.ToolDisplay;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@code POST /agents/{id}/resume/stream}: refusals are plain HTTP errors
 * before any frame is written, the accepted decision opens the stream with
 * {@code hitl_resume}, and the ending is {@code approval_required} +
 * {@code done}, {@code done}, or a typed {@code error}.
 */
class RestAgentEngineResumeStreamTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final Instant PAUSED_AT = Instant.parse("2026-11-03T10:00:00Z");

    private IConversationService conversationService;
    private RestAgentEngine restAgentEngine;
    private ScheduledExecutorService scheduler;
    private SseEventSink eventSink;
    private Sse sse;
    private OutboundSseEvent.Builder eventBuilder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conversationService = mock(IConversationService.class);
        restAgentEngine = mock(RestAgentEngine.class);
        scheduler = mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());

        eventSink = mock(SseEventSink.class);
        sse = mock(Sse.class);
        eventBuilder = mock(OutboundSseEvent.Builder.class);
        when(eventSink.isClosed()).thenReturn(false);
        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
        when(eventBuilder.comment(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(mock(OutboundSseEvent.class));
    }

    /**
     * The say stream's switch is always on here; the argument is the RESUME
     * stream's own switch.
     */
    private RestAgentEngineStreaming streaming(boolean resumeCancelOnDisconnect) {
        var streaming = new RestAgentEngineStreaming(conversationService, mock(ConversationAccessGuard.class), true, 15);
        streaming.resumeCancelOnClientDisconnect = resumeCancelOnDisconnect;
        streaming.shutdownKeepAlive();
        streaming.keepAliveScheduler = scheduler;
        streaming.restAgentEngine = restAgentEngine;
        streaming.clientGoneExecutor = Runnable::run;
        return streaming;
    }

    private static HitlDecision approved() {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        return decision;
    }

    /**
     * Opens a resume stream whose admission accepts, and returns the handler the
     * service would drive.
     */
    private StreamingResponseHandler accepted(RestAgentEngineStreaming streaming) {
        var handler = ArgumentCaptor.forClass(StreamingResponseHandler.class);
        when(restAgentEngine.resumeStreaming(eq(CONVERSATION_ID), any(), handler.capture(), any(), any(), any())).thenReturn(null);
        streaming.resumeStreaming(CONVERSATION_ID, approved(), false, true, List.of(), eventSink, sse);
        return handler.getValue();
    }

    /**
     * What the stream carried, in order: event name to its data (one leading pad
     * space removed).
     */
    private Map<Integer, String[]> sent() throws Exception {
        var names = ArgumentCaptor.forClass(String.class);
        var data = ArgumentCaptor.forClass(String.class);
        verify(eventBuilder, atLeast(0)).name(names.capture());
        verify(eventBuilder, atLeast(0)).data(any(Class.class), data.capture());
        var out = new LinkedHashMap<Integer, String[]>();
        for (int i = 0; i < names.getAllValues().size(); i++) {
            out.put(i, new String[]{names.getAllValues().get(i), data.getAllValues().get(i).substring(1)});
        }
        return out;
    }

    private List<String> sentNames() throws Exception {
        return sent().values().stream().map(e -> e[0]).toList();
    }

    private String dataOf(String event) throws Exception {
        return sent().values().stream().filter(e -> e[0].equals(event)).map(e -> e[1]).findFirst().orElseThrow();
    }

    private static SimpleConversationMemorySnapshot snapshot(ConversationState state) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(state);
        return snapshot;
    }

    // ── refusals ──

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 409, 503})
    @DisplayName("a refusal is the plain HTTP error: nothing is written and the keep-alive never starts")
    void refusalIsAPlainHttpError(int status) throws Exception {
        when(restAgentEngine.resumeStreaming(any(), any(), any(), any(), any(), any()))
                .thenReturn(Response.status(status).entity("refused").build());
        var streaming = streaming(true);

        var e = assertThrows(WebApplicationException.class,
                () -> streaming.resumeStreaming(CONVERSATION_ID, approved(), false, true, List.of(), eventSink, sse));

        assertEquals(status, e.getResponse().getStatus());
        verify(eventSink, never()).send(any());
        verify(eventSink, never()).close();
        verifyNoInteractions(sse);
        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("an exception from admission (access denied, shutting down) reaches the mappers untouched, before the sink is used")
    void admissionExceptionPropagates() {
        when(restAgentEngine.resumeStreaming(any(), any(), any(), any(), any(), any())).thenThrow(new ForbiddenException("no"));
        var streaming = streaming(true);

        assertThrows(ForbiddenException.class,
                () -> streaming.resumeStreaming(CONVERSATION_ID, approved(), false, true, List.of(), eventSink, sse));

        verifyNoInteractions(eventSink);
        verifyNoInteractions(sse);
        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
    }

    // ── the accepted stream ──

    @Test
    @DisplayName("once admitted, the keep-alive starts and nothing is written until the service reports")
    void keepAliveStartsAfterAcceptance() {
        accepted(streaming(true));

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
        verify(eventSink, never()).send(any());
    }

    @Test
    @DisplayName("hitl_resume carries the pause id and verdict, and comes before the turn's events")
    void hitlResumeFirst() throws Exception {
        var handler = accepted(streaming(true));

        handler.onResumeAccepted("1793700000000", HitlVerdict.REJECTED);
        handler.onToken("hi");

        assertEquals(List.of("hitl_resume", "token"), sentNames());
        assertEquals("{\"pauseId\":\"1793700000000\",\"verdict\":\"REJECTED\"}", dataOf("hitl_resume"));
    }

    @Test
    @DisplayName("a settled turn ends with done and no approval_required")
    void settledEndsWithDone() throws Exception {
        var handler = accepted(streaming(true));

        handler.onComplete(snapshot(ConversationState.READY));

        assertEquals(List.of("done"), sentNames());
        verify(eventSink).close();
    }

    @Test
    @DisplayName("a turn that pauses again sends approval_required with the pause id, type and call count right before done")
    void rePauseSendsApprovalRequired() throws Exception {
        var handler = accepted(streaming(true));
        var paused = snapshot(ConversationState.AWAITING_HUMAN);
        paused.setHitlPausedAt(PAUSED_AT);
        paused.setHitlPauseType("TOOL_CALL");
        var batch = new PendingToolCallBatch();
        batch.setCalls(List.of(new PendingToolCallBatch.PendingToolCall(), new PendingToolCallBatch.PendingToolCall()));
        paused.setHitlPendingToolCalls(batch);

        handler.onComplete(paused);

        assertEquals(List.of("approval_required", "done"), sentNames());
        assertEquals("{\"pauseId\":\"" + HitlDecision.pauseIdOf(PAUSED_AT) + "\",\"pauseType\":\"TOOL_CALL\",\"callCount\":2}",
                dataOf("approval_required"));
    }

    @Test
    @DisplayName("a rule pause has callCount 0; a say stream ending paused sends approval_required too")
    void rulePauseAndSayStream() throws Exception {
        var streaming = streaming(true);
        streaming.sayStreaming(CONVERSATION_ID, false, true, List.of(), inputData(), eventSink, sse);
        var handler = ArgumentCaptor.forClass(StreamingResponseHandler.class);
        verify(conversationService).sayStreaming(eq(CONVERSATION_ID), any(), any(), any(), any(), handler.capture());
        var paused = snapshot(ConversationState.AWAITING_HUMAN);
        paused.setHitlPausedAt(PAUSED_AT);
        paused.setHitlPauseType("RULE");

        handler.getValue().onComplete(paused);

        assertEquals(List.of("approval_required", "done"), sentNames());
        assertEquals("{\"pauseId\":\"" + HitlDecision.pauseIdOf(PAUSED_AT) + "\",\"pauseType\":\"RULE\",\"callCount\":0}",
                dataOf("approval_required"));
    }

    private static InputData inputData() {
        var input = new InputData();
        input.setInput("hello");
        return input;
    }

    // ── skipped / failed ──

    @Test
    @DisplayName("an accepted resume that was dropped ends with a typed error: ended -> conversation_ended")
    void skippedEnded() throws Exception {
        var handler = accepted(streaming(true));

        handler.onSkipped(snapshot(ConversationState.ENDED));

        assertEquals(List.of("error"), sentNames());
        assertTrue(dataOf("error").contains("\"code\":\"conversation_ended\""));
        verify(eventSink).close();
    }

    @Test
    @DisplayName("dropped while paused, busy or otherwise: awaiting_approval, conversation_busy, resume_dropped")
    void skippedCodes() throws Exception {
        for (var entry : Map.of(ConversationState.AWAITING_HUMAN, "awaiting_approval", ConversationState.IN_PROGRESS, "conversation_busy",
                ConversationState.READY, "resume_dropped").entrySet()) {
            setUp();
            var handler = accepted(streaming(true));
            handler.onSkipped(snapshot(entry.getKey()));
            assertTrue(dataOf("error").contains("\"code\":\"" + entry.getValue() + "\""), entry.getValue());
        }
    }

    @Test
    @DisplayName("a skipped say turn keeps its old answer: a done event, not an error")
    void skippedSayIsStillDone() throws Exception {
        var streaming = streaming(true);
        streaming.sayStreaming(CONVERSATION_ID, false, true, List.of(), inputData(), eventSink, sse);
        var handler = ArgumentCaptor.forClass(StreamingResponseHandler.class);
        verify(conversationService).sayStreaming(eq(CONVERSATION_ID), any(), any(), any(), any(), handler.capture());

        handler.getValue().onSkipped(snapshot(ConversationState.AWAITING_HUMAN));

        assertEquals(List.of("approval_required", "done"), sentNames());
    }

    @Test
    @DisplayName("a failure ends with an opaque error and the stream closes")
    void failureIsAnError() throws Exception {
        var handler = accepted(streaming(true));

        handler.onError(new IllegalStateException("mongo://secret-host exploded"));

        assertEquals(List.of("error"), sentNames());
        assertFalse(dataOf("error").contains("secret-host"));
        assertTrue(dataOf("error").contains("correlationId"));
        verify(eventSink).close();
    }

    // ── tool events ──

    @Test
    @DisplayName("tool_call carries tool and callId, plus displayName and display only when configured")
    void toolCallShapes() throws Exception {
        var handler = accepted(streaming(true));

        handler.onToolCall("lookup", "c1", null);
        handler.onToolCall("createAction", "c2", new ToolDisplay("Creating an action", Map.of("title", "Renew")));
        handler.onToolCall("labelOnly", "c3", new ToolDisplay("Looking", Map.of()));

        var payloads = sent().values().stream().map(e -> e[1]).toList();
        assertEquals("{\"tool\":\"lookup\",\"callId\":\"c1\"}", payloads.get(0));
        assertEquals("{\"tool\":\"createAction\",\"callId\":\"c2\",\"displayName\":\"Creating an action\",\"display\":{\"title\":\"Renew\"}}",
                payloads.get(1));
        assertEquals("{\"tool\":\"labelOnly\",\"callId\":\"c3\",\"displayName\":\"Looking\"}", payloads.get(2));
    }

    @Test
    @DisplayName("tool_result carries tool, callId, status and duration - nothing else")
    void toolResultShape() throws Exception {
        var handler = accepted(streaming(true));

        handler.onToolResult("lookup", "c1", "not_executed", 12);

        assertEquals("{\"tool\":\"lookup\",\"callId\":\"c1\",\"status\":\"not_executed\",\"durationMs\":12}", dataOf("tool_result"));
    }

    @Test
    @DisplayName("the name-only onToolCall(String) still produces the old tool_call payload")
    void legacyToolCall() throws Exception {
        var handler = accepted(streaming(true));

        handler.onToolCall("lookup");

        assertEquals("{\"tool\":\"lookup\"}", dataOf("tool_call"));
    }

    // ── disconnect ──

    @Test
    @DisplayName("a client that vanished cancels the resumed turn when resume-cancel-on-client-disconnect is on")
    void disconnectCancels() throws Exception {
        var handler = accepted(streaming(true));
        when(eventSink.isClosed()).thenReturn(true);

        handler.onToken("lost");

        verify(conversationService).cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL,
                RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
    }

    @Test
    @DisplayName("and lets the resumed turn finish and persist when it is off")
    void disconnectDoesNotCancelWhenOff() throws Exception {
        var handler = accepted(streaming(false));
        when(eventSink.isClosed()).thenReturn(true);

        handler.onToken("lost");

        verify(conversationService, never()).cancelConversation(any(), any(), any());
    }

    @Test
    @DisplayName("by default a vanished /resume/stream client does NOT cancel the resumed turn, even though /stream's switch is on")
    void resumeStreamDoesNotCancelByDefault() throws Exception {
        // the field is left at its default; the say switch (constructor) is true
        var streaming = new RestAgentEngineStreaming(conversationService, mock(ConversationAccessGuard.class), true, 15);
        streaming.shutdownKeepAlive();
        streaming.keepAliveScheduler = scheduler;
        streaming.restAgentEngine = restAgentEngine;
        streaming.clientGoneExecutor = Runnable::run;
        assertFalse(streaming.resumeCancelOnClientDisconnect);
        var handler = accepted(streaming);
        when(eventSink.isClosed()).thenReturn(true);

        handler.onToken("lost");

        verify(conversationService, never()).cancelConversation(any(), any(), any());
    }

    @Test
    @DisplayName("the say stream keeps its own switch whatever the resume switch says")
    void sayStreamKeepsItsOwnSwitch() throws Exception {
        var streaming = streaming(false);
        streaming.sayStreaming(CONVERSATION_ID, false, true, List.of(), inputData(), eventSink, sse);
        var handler = ArgumentCaptor.forClass(StreamingResponseHandler.class);
        verify(conversationService).sayStreaming(eq(CONVERSATION_ID), any(), any(), any(), any(), handler.capture());
        when(eventSink.isClosed()).thenReturn(true);

        handler.getValue().onToken("lost");

        verify(conversationService).cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL,
                RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
    }

    @Test
    @DisplayName("sends the events of an accepted resume as an ordered list")
    void fullResumeSequence() throws Exception {
        var handler = accepted(streaming(true));
        var events = new ArrayList<String>();

        handler.onResumeAccepted("1", HitlVerdict.APPROVED);
        handler.onToolCall("lookup", "c1", null);
        handler.onToolResult("lookup", "c1", "ok", 3);
        handler.onToken("done!");
        handler.onComplete(snapshot(ConversationState.READY));

        events.addAll(sentNames());
        assertEquals(List.of("hitl_resume", "tool_call", "tool_result", "token", "done"), events);
    }
}
