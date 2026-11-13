/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A {@code /v1} stream nobody reads any more must stop costing model calls and
 * tool calls: the turn behind it is cancelled — once, only while it is the turn
 * running, and never after it has settled.
 */
class OpenAiStreamAbandonmentTest {

    private static final String CONVERSATION_ID = "66c0ffee0000000000000001";
    private static final String USER_ID = "u_812";
    private static final int TIMEOUT_SECONDS = 1;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IConversationService conversationService;
    private SimpleMeterRegistry meterRegistry;
    private OpenAiConversationBridge bridge;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        meterRegistry = new SimpleMeterRegistry();
        bridge = new OpenAiConversationBridge(conversationService, mock(IUserConversationStore.class),
                mock(IUserMemoryStore.class), new OpenAiMessageMapper(objectMapper, 5),
                OpenAiTestFixtures.config(b -> b.requestTimeoutSeconds = TIMEOUT_SECONDS),
                meterRegistry, mock(ResourceAccessGuard.class), mock(IConversationMemoryStore.class));
        bridge.initMetrics();
    }

    /**
     * A stateful turn: its conversation belongs to the chat and is never ended
     * here.
     */
    private static OpenAiConversationBridge.PreparedTurn statefulTurn() {
        return new OpenAiConversationBridge.PreparedTurn(CONVERSATION_ID, new InputData(), false, "intent");
    }

    private void givenStreamingEmits(Consumer<IConversationService.StreamingResponseHandler> script) throws Exception {
        doAnswer(invocation -> {
            script.accept(invocation.getArgument(5));
            return null;
        }).when(conversationService).sayStreaming(any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    private static SimpleConversationMemorySnapshot snapshot(ConversationState state) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setConversationState(state);
        var output = new ConversationOutput();
        output.put("output", List.of("answer"));
        snapshot.setConversationOutputs(List.of(output));
        return snapshot;
    }

    private void verifyCancelledOnce(String actor) throws Exception {
        verify(conversationService, times(1)).cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL, actor);
        verify(conversationService, times(1)).cancelConversation(any(), any(), any());
    }

    private void verifyNeverCancelled() throws Exception {
        verify(conversationService, never()).cancelConversation(any(), any(), any());
        verify(conversationService, never()).cancelConversation(any(), any());
    }

    private double outcomeCount(String outcome) {
        var counter = meterRegistry.find("eddi.openai.requests").tag("mode", "stream").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    /** Accepts writes until {@link #disconnect}, then fails every one. */
    private static final class DisconnectingStream extends OutputStream {
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private volatile boolean disconnected;

        void disconnect() {
            disconnected = true;
        }

        @Override
        public void write(int b) throws IOException {
            if (disconnected) {
                throw new IOException("Connection was closed");
            }
            received.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (disconnected) {
                throw new IOException("Connection was closed");
            }
            received.write(b, off, len);
        }

        String body() {
            return received.toString(StandardCharsets.UTF_8);
        }
    }

    // ─── client disconnect ───

    @Test
    void writeFailure_cancelsTheTurnExactlyOnce_andLaterFramesAreDropped() throws Exception {
        var out = new DisconnectingStream();
        givenStreamingEmits(handler -> {
            handler.onTaskStart(null, "ai.labs.llm", 0);
            handler.onToken("before ");
            out.disconnect();
            handler.onToken("lost ");
            handler.onToken("dropped ");
            handler.onToken("dropped-too");
            handler.onComplete(snapshot(ConversationState.READY));
        });

        bridge.stream(statefulTurn(), new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        verifyCancelledOnce(OpenAiConversationBridge.CANCELLED_BY_CLIENT_DISCONNECT);
        assertTrue(out.body().contains("before "), out.body());
        assertFalse(out.body().contains("dropped"), "nothing is written after the client left: " + out.body());
        assertEquals(1.0, outcomeCount(OpenAiConversationBridge.OUTCOME_CLIENT_GONE));
        assertEquals(0.0, outcomeCount("error"), "a client that left is not an agent error");
        verify(conversationService, never()).endConversation(any());
    }

    @Test
    void writeFailure_stopsWaitingForTheTurn() throws Exception {
        // The turn never reports a terminal callback here (as a cancelled turn whose
        // outcome is discarded may not); the request must not sit out the timeout.
        var out = new DisconnectingStream();
        out.disconnect();
        givenStreamingEmits(handler -> {
            handler.onTaskStart(null, "ai.labs.llm", 0);
            handler.onToken("x");
        });

        long start = System.nanoTime();
        bridge.stream(statefulTurn(), new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS),
                "returned after " + elapsedMs + " ms — it waited for the request timeout");
        verifyCancelledOnce(OpenAiConversationBridge.CANCELLED_BY_CLIENT_DISCONNECT);
    }

    @Test
    void normalCompletion_cancelsNothing() throws Exception {
        var out = new ByteArrayOutputStream();
        givenStreamingEmits(handler -> {
            handler.onTaskStart(null, "ai.labs.llm", 0);
            handler.onToken("all ");
            handler.onToken("read");
            handler.onComplete(snapshot(ConversationState.READY));
        });

        bridge.stream(statefulTurn(), new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        verifyNeverCancelled();
        assertTrue(out.toString(StandardCharsets.UTF_8).trim().endsWith("data: [DONE]"));
        assertEquals(1.0, outcomeCount("ok"));
    }

    @Test
    void writeFailureWhileReportingAPause_neverCancelsThePendingApproval() throws Exception {
        // The turn is over and its pause persisted; cancelling now would resolve the
        // approval nobody has decided yet.
        var out = new DisconnectingStream();
        out.disconnect();
        givenStreamingEmits(handler -> {
            handler.onTaskStart(null, "ai.labs.llm", 0);
            handler.onComplete(snapshot(ConversationState.AWAITING_HUMAN));
        });

        bridge.stream(statefulTurn(), new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        verifyNeverCancelled();
    }

    @Test
    void cancelOnClientDisconnectOff_letsTheTurnFinish() throws Exception {
        bridge.cancelOnClientDisconnect = false;
        var out = new DisconnectingStream();
        out.disconnect();
        givenStreamingEmits(handler -> {
            handler.onTaskStart(null, "ai.labs.llm", 0);
            handler.onToken("x");
            handler.onToken("y");
        });

        bridge.stream(statefulTurn(), new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        verifyNeverCancelled();
        assertEquals(1.0, outcomeCount(OpenAiConversationBridge.OUTCOME_CLIENT_GONE));
    }

    @Test
    void failedCancel_doesNotBreakTheStream() throws Exception {
        doAnswer(invocation -> {
            throw new IllegalStateException("store down");
        }).when(conversationService).cancelConversation(any(), any(), any());
        var out = new DisconnectingStream();
        out.disconnect();
        givenStreamingEmits(handler -> {
            handler.onTaskStart(null, "ai.labs.llm", 0);
            handler.onToken("x");
        });

        bridge.stream(statefulTurn(), new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        verifyCancelledOnce(OpenAiConversationBridge.CANCELLED_BY_CLIENT_DISCONNECT);
    }

    // ─── request timeout ───

    @Test
    void timeout_cancelsTheRunningTurnOnce() throws Exception {
        var out = new ByteArrayOutputStream();
        var captured = new AtomicReference<IConversationService.StreamingResponseHandler>();
        givenStreamingEmits(handler -> {
            captured.set(handler);
            handler.onTaskStart(null, "ai.labs.llm", 0);
            // ...and the turn runs past the request timeout.
        });

        bridge.stream(statefulTurn(), new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        verifyCancelledOnce(OpenAiConversationBridge.CANCELLED_BY_REQUEST_TIMEOUT);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"code\":\"" + OpenAiErrorResponse.CODE_TIMEOUT + "\""),
                out.toString(StandardCharsets.UTF_8));
        // Late events of the cancelled turn neither write nor cancel again.
        captured.get().onTaskStart(null, "ai.labs.output", 1);
        captured.get().onToken("late");
        verifyCancelledOnce(OpenAiConversationBridge.CANCELLED_BY_REQUEST_TIMEOUT);
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("late"));
        assertEquals(1.0, outcomeCount("timeout"));
    }

    @Test
    void timeoutWhileStillQueued_cancelsOnlyOnceTheTurnStarts() throws Exception {
        // Queued behind another turn of the same conversation: a cancel now would
        // stop THAT turn. The cancel waits for this turn's own first sign of life.
        var captured = new AtomicReference<IConversationService.StreamingResponseHandler>();
        givenStreamingEmits(captured::set);

        bridge.stream(statefulTurn(), new OpenAiSseWriter(new ByteArrayOutputStream(), objectMapper, "id", "m", 1L, false));

        verifyNeverCancelled();
        captured.get().onTaskStart(null, "ai.labs.llm", 0);
        verifyCancelledOnce(OpenAiConversationBridge.CANCELLED_BY_REQUEST_TIMEOUT);
        captured.get().onTaskStart(null, "ai.labs.output", 1);
        verifyCancelledOnce(OpenAiConversationBridge.CANCELLED_BY_REQUEST_TIMEOUT);
    }

    @Test
    void timeoutWhileQueued_thenSkipped_neverCancels() throws Exception {
        var captured = new AtomicReference<IConversationService.StreamingResponseHandler>();
        givenStreamingEmits(captured::set);

        bridge.stream(statefulTurn(), new OpenAiSseWriter(new ByteArrayOutputStream(), objectMapper, "id", "m", 1L, false));
        captured.get().onSkipped(snapshot(ConversationState.READY));

        verifyNeverCancelled();
    }

    // ─── the caller-gives-up stamp ───

    @Test
    void streamedTurn_isStampedWithWhenTheRequestStopsWaiting() throws Exception {
        givenStreamingEmits(handler -> handler.onComplete(snapshot(ConversationState.READY)));
        var turn = statefulTurn();
        long before = System.currentTimeMillis();

        bridge.stream(turn, new OpenAiSseWriter(new ByteArrayOutputStream(), objectMapper, "id", "m", 1L, false));

        Long givesUpAt = turn.inputData().getCallerGivesUpAtEpochMs();
        assertNotNull(givesUpAt, "a turn still queued when the request times out must be dropped unrun");
        assertTrue(givesUpAt >= before + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS), "stamp " + givesUpAt);
        assertTrue(givesUpAt <= System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS), "stamp " + givesUpAt);
    }

    @Test
    void syncTurn_isStamped_andADroppedQueuedTurnAnswersTimeout() throws Exception {
        var sent = ArgumentCaptor.forClass(InputData.class);
        doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onCallerGone(snapshot(ConversationState.READY));
            return null;
        }).when(conversationService).say(any(), anyBoolean(), anyBoolean(), any(), sent.capture(), anyBoolean(), any());

        var failure = assertThrows(Exception.class, () -> bridge.sendAndWait(CONVERSATION_ID, new InputData()));

        assertNotNull(sent.getValue().getCallerGivesUpAtEpochMs());
        OpenAiApiException apiException = bridge.asApiException(failure);
        assertEquals(504, apiException.getStatus(), "a turn dropped for its timed-out caller is a timeout, not busy");
    }
}
