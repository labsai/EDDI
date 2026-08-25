/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.utils.LogSanitizer;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAgentEngineStreaming}. Tests the SSE event mapping,
 * JSON serialization helpers, and error handling.
 */
class RestAgentEngineStreamingTest {

    private IConversationService conversationService;
    private ConversationAccessGuard conversationAccessGuard;
    private RestAgentEngineStreaming streaming;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        conversationAccessGuard = mock(ConversationAccessGuard.class);
        streaming = new RestAgentEngineStreaming(conversationService, conversationAccessGuard);
    }

    @Nested
    @DisplayName("LogSanitizer.sanitize (centralized utility)")
    class SanitizeForLog {

        @Test
        @DisplayName("should replace newlines, carriage returns, and tabs")
        void replacesControlChars() {
            assertEquals("hello_world", LogSanitizer.sanitize("hello\nworld"));
            assertEquals("hello_world", LogSanitizer.sanitize("hello\rworld"));
            assertEquals("hello_world", LogSanitizer.sanitize("hello\tworld"));
            assertEquals("null", LogSanitizer.sanitize(null));
        }
    }

    @Nested
    @DisplayName("escapeJson (private)")
    class EscapeJson {

        @Test
        @DisplayName("should escape special characters")
        void escapesSpecialChars() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("escapeJson", String.class);
            method.setAccessible(true);

            assertEquals("", method.invoke(streaming, (String) null));
            assertEquals("hello", method.invoke(streaming, "hello"));
            assertEquals("say \\\"hi\\\"", method.invoke(streaming, "say \"hi\""));
            assertEquals("line1\\nline2", method.invoke(streaming, "line1\nline2"));
        }

        /**
         * The replace-chain this grew from covered five characters and left every other
         * control character raw inside a JSON string, which is invalid per RFC 8259 §7
         * — so a strict client cannot parse the event at all. None of the values
         * reaching {@code escapeJson} are ours: a tool name comes from an LLM, an error
         * summary from an exception message, a conversation id straight off the request
         * path.
         */
        @Test
        @DisplayName("escapes every control character, so the event stays parseable JSON")
        void escapesAllControlCharacters() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("escapeJson", String.class);
            method.setAccessible(true);

            assertEquals("a\\u0000b", method.invoke(streaming, "a\u0000b"));
            // Backspace and form-feed have their own two-character JSON escapes;
            // the six-character form is only for the control characters that do
            // not. (Spelling it out here on purpose: a literal backslash-u in a
            // Java comment is processed by the lexer and fails to compile.)
            assertEquals("a\\bb", method.invoke(streaming, "a\bb"));
            assertEquals("a\\fb", method.invoke(streaming, "a\fb"));
            assertEquals("a\\u001fb", method.invoke(streaming, "a\u001fb"));
            // Legal JSON, but a line terminator in JavaScript — and this goes to a
            // browser.
            assertEquals("a\\u2028b", method.invoke(streaming, "a\u2028b"));
            assertEquals("a\\u2029b", method.invoke(streaming, "a\u2029b"));

            // The whole point: what comes out has to survive a real JSON parser.
            String hostile = "conv\u0000\u0008\u001f\u2028\"x\\y";
            String event = String.format("{\"message\":\"%s\"}", method.invoke(streaming, hostile));
            assertEquals(hostile, new ObjectMapper().readTree(event).get("message").asText());
            assertEquals("col1\\tcol2", method.invoke(streaming, "col1\tcol2"));
            assertEquals("path\\\\file", method.invoke(streaming, "path\\file"));
        }
    }

    @Nested
    @DisplayName("toJsonArray (private)")
    class ToJsonArray {

        @Test
        @DisplayName("should serialize list to JSON array")
        void serializesList() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJsonArray", Object.class);
            method.setAccessible(true);

            assertEquals("[\"a\",\"b\",\"c\"]", method.invoke(streaming, List.of("a", "b", "c")));
            assertEquals("[]", method.invoke(streaming, List.of()));
        }

        @Test
        @DisplayName("should return [] for non-list input")
        void nonList() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJsonArray", Object.class);
            method.setAccessible(true);

            assertEquals("[]", method.invoke(streaming, "not-a-list"));
        }
    }

    @Nested
    @DisplayName("toJson snapshot (private)")
    class ToJsonSnapshot {

        @Test
        @DisplayName("should serialize snapshot with conversation state")
        void serializesSnapshot() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJson", SimpleConversationMemorySnapshot.class);
            method.setAccessible(true);

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);

            String json = (String) method.invoke(streaming, snapshot);

            assertTrue(json.contains("READY"));
        }

        @Test
        @DisplayName("should include conversationOutputs when present")
        void includesOutputs() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJson", SimpleConversationMemorySnapshot.class);
            method.setAccessible(true);

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            var output = new ConversationOutput();
            output.put("output", List.of("Hello!"));
            snapshot.setConversationOutputs(List.of(output));

            String json = (String) method.invoke(streaming, snapshot);

            assertTrue(json.contains("conversationOutputs"));
        }

        @Test
        @DisplayName("carries the pause identity: hitlPausedAt as the ISO instant, verbatim")
        void includesPauseIdentity() throws Exception {
            // hitlPausedAt is the only field distinguishing one pause of a turn
            // from the next. The done event omitting it left streamed pauses
            // identityless: the Manager's settle-poll read every re-pause as
            // the pause it had already decided and spun to its timeout with
            // the Approve button dead. The value must be the same ISO_INSTANT
            // string the REST snapshot serializes, so cross-channel string
            // comparison stays sound.
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJson", SimpleConversationMemorySnapshot.class);
            method.setAccessible(true);

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPausedAt(Instant.parse("2026-08-16T00:05:41.984599700Z"));

            String json = (String) method.invoke(streaming, snapshot);

            assertTrue(json.contains("\"hitlPausedAt\":\"2026-08-16T00:05:41.984599700Z\""), json);
        }

        @Test
        @DisplayName("omits hitlPausedAt when the conversation is not paused")
        void omitsPauseIdentityWhenAbsent() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJson", SimpleConversationMemorySnapshot.class);
            method.setAccessible(true);

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);

            String json = (String) method.invoke(streaming, snapshot);

            assertFalse(json.contains("hitlPausedAt"), json);
        }
    }

    @Nested
    @DisplayName("sayStreaming")
    class SayStreaming {

        @Test
        @DisplayName("should delegate to conversationService and set up handler")
        void delegatesToService() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var inputData = new InputData();
            inputData.setInput("Hello");

            when(eventSink.isClosed()).thenReturn(false);

            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            verify(conversationService).sayStreaming(eq("conv-1"), eq(false), eq(false), eq(List.of()),
                    eq(inputData), any(IConversationService.StreamingResponseHandler.class));
        }

        @Test
        @DisplayName("should send error event when service throws")
        void sendsErrorOnException() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var eventBuilder = mock(OutboundSseEvent.Builder.class);
            var sseEvent = mock(OutboundSseEvent.class);
            var inputData = new InputData();
            inputData.setInput("Hello");

            when(eventSink.isClosed()).thenReturn(false);
            when(sse.newEventBuilder()).thenReturn(eventBuilder);
            when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
            when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
            when(eventBuilder.build()).thenReturn(sseEvent);

            doThrow(new RuntimeException("Service failed"))
                    .when(conversationService).sayStreaming(anyString(), any(), any(), any(), any(), any());

            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            // Should have sent an error event
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
            verify(eventBuilder, atLeastOnce()).name("error");
        }

        @Test
        @DisplayName("cascade handler methods emit cascade_step_start and cascade_escalation SSE events")
        void forwardsCascadeEvents() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var eventBuilder = mock(OutboundSseEvent.Builder.class);
            var sseEvent = mock(OutboundSseEvent.class);
            var inputData = new InputData();
            inputData.setInput("Hi");

            when(eventSink.isClosed()).thenReturn(false);
            when(sse.newEventBuilder()).thenReturn(eventBuilder);
            when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
            when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
            when(eventBuilder.build()).thenReturn(sseEvent);

            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            // Capture the handler the streaming endpoint wired up, then drive its cascade
            // callbacks.
            var cap = org.mockito.ArgumentCaptor.forClass(IConversationService.StreamingResponseHandler.class);
            verify(conversationService).sayStreaming(eq("conv-1"), any(), any(), any(), any(), cap.capture());
            var handler = cap.getValue();

            handler.onCascadeStepStart(0, "openai", "gpt-4o-mini", 2);
            verify(eventBuilder).name("cascade_step_start");
            verify(eventSink).send(sseEvent);

            handler.onCascadeEscalation(0, 1, 0.4, 0.7, "low_confidence", 42L);
            verify(eventBuilder).name("cascade_escalation");
            verify(eventSink, times(2)).send(sseEvent);
        }

        @Test
        @DisplayName("A1: a caller who does not own the conversation is denied (403) before the turn runs")
        void deniesForeignConversation() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var inputData = new InputData();
            inputData.setInput("Hello");

            // user B posting into user A's conversation
            doThrow(new ForbiddenException("Access denied: you do not own this conversation"))
                    .when(conversationAccessGuard).requireConversationOwner("conv-of-user-a");

            assertThrows(ForbiddenException.class,
                    () -> streaming.sayStreaming("conv-of-user-a", false, false, List.of(), inputData, eventSink, sse));

            // The turn must not have been started, and the denial must NOT be
            // downgraded into an SSE 'error' event on an otherwise 200 stream.
            verify(conversationService, never()).sayStreaming(anyString(), any(), any(), any(), any(), any());
            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("A1: the owner check runs on every streaming turn")
        void checksOwnershipOnEveryTurn() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var inputData = new InputData();
            inputData.setInput("Hello");

            when(eventSink.isClosed()).thenReturn(false);

            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            verify(conversationAccessGuard).requireConversationOwner("conv-1");
        }

        @Test
        @DisplayName("should handle closed sink gracefully")
        void handleClosedSink() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var inputData = new InputData();

            when(eventSink.isClosed()).thenReturn(true);

            doThrow(new RuntimeException("Service failed"))
                    .when(conversationService).sayStreaming(anyString(), any(), any(), any(), any(), any());

            // Should not throw
            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);
        }
    }

    /**
     * A12 — the non-streaming surfaces stopped echoing raw exception text
     * (correlation id + fixed message instead), because a sneaky-thrown
     * {@code ResourceStoreException} from the store layer names collections, hosts
     * and replica-set members. The SSE twin kept pushing {@code error.getMessage()}
     * straight down a channel a browser renders — the same reconnaissance, over a
     * different pipe.
     */
    @Nested
    @DisplayName("A12 — SSE error events are opaque")
    class OpaqueErrorEvents {

        private static final String LEAKY = "Command failed on replica-set member mongo-3.internal:27017, "
                + "db=eddi, collection=conversationmemories";

        /** Captures the JSON body of every SSE frame the endpoint emitted. */
        private List<String> sentPayloads(OutboundSseEvent.Builder eventBuilder) {
            var payloads = ArgumentCaptor.forClass(String.class);
            verify(eventBuilder, atLeastOnce()).data(any(Class.class), payloads.capture());
            return payloads.getAllValues();
        }

        private OutboundSseEvent.Builder wire(Sse sse) {
            var eventBuilder = mock(OutboundSseEvent.Builder.class);
            var sseEvent = mock(OutboundSseEvent.class);
            when(sse.newEventBuilder()).thenReturn(eventBuilder);
            when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
            when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
            when(eventBuilder.build()).thenReturn(sseEvent);
            return eventBuilder;
        }

        @Test
        @DisplayName("a startup failure does not leak the store's exception text")
        void startupFailureIsOpaque() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var eventBuilder = wire(sse);
            when(eventSink.isClosed()).thenReturn(false);

            doThrow(new RuntimeException(LEAKY))
                    .when(conversationService).sayStreaming(anyString(), any(), any(), any(), any(), any());

            var inputData = new InputData();
            inputData.setInput("Hello");
            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            String errorFrame = sentPayloads(eventBuilder).getLast();
            assertFalse(errorFrame.contains("mongo-3.internal"), "the SSE error frame must not name deployment internals");
            assertFalse(errorFrame.contains("conversationmemories"));
            assertTrue(errorFrame.contains("correlationId"), "the caller needs a handle to quote in a support ticket");
        }

        @Test
        @DisplayName("a mid-stream failure does not leak the store's exception text")
        void midStreamFailureIsOpaque() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var eventBuilder = wire(sse);
            when(eventSink.isClosed()).thenReturn(false);

            var inputData = new InputData();
            inputData.setInput("Hello");
            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            var captor = ArgumentCaptor.forClass(IConversationService.StreamingResponseHandler.class);
            verify(conversationService).sayStreaming(eq("conv-1"), any(), any(), any(), any(), captor.capture());

            captor.getValue().onError(new IllegalStateException(LEAKY));

            String errorFrame = sentPayloads(eventBuilder).getLast();
            assertFalse(errorFrame.contains("mongo-3.internal"), "the SSE error frame must not name deployment internals");
            assertFalse(errorFrame.contains("conversationmemories"));
            assertTrue(errorFrame.contains("correlationId"));
        }
    }

    /**
     * F6 — the streaming endpoint receives only {@link SseEventSink}/{@link Sse}
     * and never signalled cancellation, so closing the tab at token 5 of 4000 still
     * streamed (and billed) the whole completion, possibly escalating through the
     * whole model cascade.
     * <p>
     * A JAX-RS {@code ConnectionCallback} is not the mechanism here: RESTEasy
     * Reactive registers connection callbacks into a request property that nothing
     * ever reads, so one would cancel nothing. The observable signal is
     * {@code SseEventSink.isClosed()} (backed by
     * {@code serverResponse().closed()}), which is why the disconnect is detected
     * on the next outbound frame — i.e. within one token boundary.
     */
    @Nested
    @DisplayName("F6 — client disconnect cancels the in-flight turn")
    class ClientDisconnect {

        private SseEventSink eventSink;

        /** Starts a stream and returns the handler the endpoint wired up. */
        private IConversationService.StreamingResponseHandler start(String conversationId) throws Exception {
            eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var eventBuilder = mock(OutboundSseEvent.Builder.class);
            var sseEvent = mock(OutboundSseEvent.class);
            when(sse.newEventBuilder()).thenReturn(eventBuilder);
            when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
            when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
            when(eventBuilder.build()).thenReturn(sseEvent);
            when(eventSink.isClosed()).thenReturn(false);

            var inputData = new InputData();
            inputData.setInput("Hello");
            streaming.sayStreaming(conversationId, false, false, List.of(), inputData, eventSink, sse);

            var captor = ArgumentCaptor.forClass(IConversationService.StreamingResponseHandler.class);
            verify(conversationService).sayStreaming(eq(conversationId), any(), any(), any(), any(), captor.capture());
            return captor.getValue();
        }

        private SimpleConversationMemorySnapshot readySnapshot() {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            return snapshot;
        }

        @Test
        @DisplayName("a token emitted after the client vanished cancels the turn, exactly once")
        void tokenAfterDisconnectCancelsOnce() throws Exception {
            var handler = start("conv-1");

            // The client closed the tab: Vert.x closes the response, so the sink
            // reports closed from the next frame onwards.
            when(eventSink.isClosed()).thenReturn(true);

            handler.onToken("tok-5");
            handler.onToken("tok-6");
            handler.onTaskComplete(new TaskId("ai.labs.llm"), "langchain", 5L, Map.of());

            verify(conversationService, times(1)).cancelConversation("conv-1",
                    ControlSignal.CANCEL_GRACEFUL, RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
            // …and nothing is written to a sink the client is no longer reading.
            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("a send that fails because the sink closed mid-write also cancels")
        void sendFailureOnClosedSinkCancels() throws Exception {
            var handler = start("conv-4");

            // The sink closes between the isClosed() pre-check and the write, which is
            // when RESTEasy Reactive throws IllegalStateException("Already closed").
            when(eventSink.isClosed()).thenReturn(false, true);
            doThrow(new IllegalStateException("Already closed"))
                    .when(eventSink).send(any(OutboundSseEvent.class));

            handler.onToken("tok-5");

            verify(conversationService, times(1)).cancelConversation("conv-4",
                    ControlSignal.CANCEL_GRACEFUL, RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
        }

        @Test
        @DisplayName("a send failure on a still-open sink is NOT a disconnect and does not cancel")
        void sendFailureOnOpenSinkDoesNotCancel() throws Exception {
            var handler = start("conv-5");

            // A payload/serialization fault, not a vanished client.
            doThrow(new RuntimeException("bad payload"))
                    .when(eventSink).send(any(OutboundSseEvent.class));

            handler.onToken("tok-5");

            verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("a stream that completes normally is never cancelled")
        void normalCompletionDoesNotCancel() throws Exception {
            var handler = start("conv-2");

            handler.onToken("hi");
            handler.onComplete(readySnapshot());

            verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("a sink already closed when the terminal frame is emitted is not a disconnect")
        void closedAtTerminalFrameDoesNotCancel() throws Exception {
            var handler = start("conv-3");

            // Client went away right as the turn finished — the answer is already
            // produced, so there is nothing left to cancel.
            when(eventSink.isClosed()).thenReturn(true);
            handler.onComplete(readySnapshot());

            verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        }

        /**
         * A cancelled turn is DISCARDED, not saved: ConversationService deliberately
         * skips persistence for a cancelled turn, so the user's own message and
         * everything produced so far are lost and the conversation settles on
         * EXECUTION_INTERRUPTED. On flaky networks (proxy idle timeouts, a phone
         * switching from Wi-Fi to cellular) that trades a saved exchange for a saved
         * completion — which is the wrong trade for some deployments, hence the switch.
         */
        @Test
        @DisplayName("with eddi.streaming.cancel-on-client-disconnect off, a disconnect lets the turn finish and persist")
        void disconnectDoesNotCancelWhenTheSwitchIsOff() throws Exception {
            streaming = new RestAgentEngineStreaming(conversationService, conversationAccessGuard, false);

            var handler = start("conv-6");
            when(eventSink.isClosed()).thenReturn(true);

            handler.onToken("tok-5");
            handler.onToken("tok-6");
            handler.onTaskComplete(new TaskId("ai.labs.llm"), "langchain", 5L, Map.of());

            verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        }

        /**
         * The behaviour is operator-configurable through a real config key — not a
         * hardcoded constant with a doc comment. Pins the key name and the shipped
         * default so neither can drift away from the documented semantics.
         */
        @Test
        @DisplayName("the disconnect cancel is bound to eddi.streaming.cancel-on-client-disconnect, defaulting to on")
        void disconnectCancelIsBoundToAConfigProperty() throws Exception {
            var constructor = RestAgentEngineStreaming.class.getDeclaredConstructor(
                    IConversationService.class, ConversationAccessGuard.class, boolean.class);

            String defaultValue = null;
            for (Annotation[] parameterAnnotations : constructor.getParameterAnnotations()) {
                for (Annotation annotation : parameterAnnotations) {
                    if (annotation instanceof ConfigProperty configProperty
                            && "eddi.streaming.cancel-on-client-disconnect".equals(configProperty.name())) {
                        defaultValue = configProperty.defaultValue();
                    }
                }
            }

            assertNotNull(defaultValue,
                    "the disconnect cancel must be bound to eddi.streaming.cancel-on-client-disconnect so operators can turn it off");
            assertTrue(Boolean.parseBoolean(defaultValue),
                    "the shipped default must keep the cost-saving cancel enabled");
        }
    }

    /**
     * The SSE grammar is `field ":" [ space ] value`, and every consumer strips one
     * leading space per {@code data:} line — it cannot tell the separator from the
     * payload's own first character. RESTEasy Reactive writes {@code data:} with no
     * separator, so an unpadded payload beginning with a space arrived one space
     * short.
     * <p>
     * Observed: the model emitted {@code "-"} then {@code " alpha"} and the client
     * reassembled {@code "-alpha"} — no longer a Markdown list item — while words
     * split across tokens ran together. Whole replies rendered as one mangled
     * paragraph.
     */
    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("SSE data lines are padded so a leading space survives")
    class DataLinePadding {

        @Test
        @DisplayName("a bare carriage return is normalised so its continuation line stays padded")
        void carriageReturnContinuationIsPadded() {
            // RESTEasy's SSE serializer starts a new data: line on \r as well
            // as \n - an unnormalised \r would produce an UNPADDED continuation
            // whose first character the consumer then eats.
            String padded = RestAgentEngineStreaming.padDataLines("a\rb\r\nc");
            assertEquals(" a\n b\n c", padded);
        }

        @org.junit.jupiter.api.Test
        void aLeadingSpaceSurvivesTheConsumersStrip() {
            String padded = RestAgentEngineStreaming.padDataLines(" alpha");
            // What the consumer does: drop exactly one leading space per line.
            assertEquals(" alpha", stripOneSpacePerLine(padded));
        }

        @org.junit.jupiter.api.Test
        void everyLineIsPadded_notOnlyTheFirst() {
            // RESTEasy emits one data: line per newline, so an indented continuation
            // line loses a space of its own indentation unless each line is padded.
            String value = "a\n  indented";
            assertEquals(value, stripOneSpacePerLine(RestAgentEngineStreaming.padDataLines(value)));
        }

        @org.junit.jupiter.api.Test
        void ordinaryPayloadsRoundTripUnchanged() {
            for (String value : new String[]{"-", "plain token", "{\"taskId\":\"x\"}", "line1\nline2"}) {
                assertEquals(value, stripOneSpacePerLine(RestAgentEngineStreaming.padDataLines(value)),
                        "round-trip must be lossless for: " + value);
            }
        }

        @org.junit.jupiter.api.Test
        void nullAndEmptyAreLeftAlone() {
            assertNull(RestAgentEngineStreaming.padDataLines(null));
            assertEquals("", RestAgentEngineStreaming.padDataLines(""));
        }

        /** The consumer half of the contract, per the SSE spec. */
        private String stripOneSpacePerLine(String wire) {
            var out = new StringBuilder();
            String[] lines = wire.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    out.append('\n');
                }
                String line = lines[i];
                out.append(line.startsWith(" ") ? line.substring(1) : line);
            }
            return out.toString();
        }
    }

    /**
     * The known client conditions {@code ConversationService.sayStreaming} rejects
     * synchronously must surface as TYPED error events —
     * {@code {"message":…,"code":…}} — not the opaque internal-error shape.
     * Observed live: input sent into an AWAITING_HUMAN conversation was refused
     * correctly by the backend but reached the client as
     * {@code {"message":"Internal server error"}}, which the Manager rendered as a
     * dead error blob instead of re-showing the approval banner.
     */
    @Nested
    @DisplayName("sayStreaming known client conditions (typed error events)")
    class KnownConditionErrorEvents {

        private SseEventSink eventSink;
        private Sse sse;
        private OutboundSseEvent.Builder eventBuilder;
        private ArgumentCaptor<String> payloads;

        @BeforeEach
        void wireSse() {
            eventSink = mock(SseEventSink.class);
            sse = mock(Sse.class);
            eventBuilder = mock(OutboundSseEvent.Builder.class);
            var sseEvent = mock(OutboundSseEvent.class);
            payloads = ArgumentCaptor.forClass(String.class);
            when(eventSink.isClosed()).thenReturn(false);
            when(sse.newEventBuilder()).thenReturn(eventBuilder);
            when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
            when(eventBuilder.data(any(Class.class), payloads.capture())).thenReturn(eventBuilder);
            when(eventBuilder.build()).thenReturn(sseEvent);
        }

        private String errorPayloadFor(Exception thrown) throws Exception {
            doThrow(thrown).when(conversationService)
                    .sayStreaming(anyString(), any(), any(), any(), any(), any());
            var inputData = new InputData();
            inputData.setInput("Hello");
            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);
            verify(eventBuilder, atLeastOnce()).name("error");
            return payloads.getValue();
        }

        @Test
        @DisplayName("awaiting approval → code=awaiting_approval with the twin's 409 message")
        void awaitingApprovalIsTyped() throws Exception {
            String message = "Conversation is awaiting human approval — a reviewer must resolve it via"
                    + " POST /agents/conv-1/resume (or cancel) before new input is accepted";
            String payload = errorPayloadFor(
                    new IConversationService.ConversationAwaitingApprovalException(message));

            assertTrue(payload.contains("\"code\":\"awaiting_approval\""), payload);
            assertTrue(payload.contains("awaiting human approval"), payload);
            assertFalse(payload.contains("Internal server error"), payload);
        }

        @Test
        @DisplayName("conversation ended → code=conversation_ended")
        void conversationEndedIsTyped() throws Exception {
            String payload = errorPayloadFor(
                    new IConversationService.ConversationEndedException("Conversation has ended!"));

            assertTrue(payload.contains("\"code\":\"conversation_ended\""), payload);
            assertFalse(payload.contains("Internal server error"), payload);
        }

        @Test
        @DisplayName("agent not ready → fixed text, NOT the message naming environment and agentId")
        void agentNotReadyDisclosesNothing() throws Exception {
            String payload = errorPayloadFor(new IConversationService.AgentNotReadyException(
                    "Agent not deployed (environment=restricted, conversationId=conv-1, version=7)"));

            assertTrue(payload.contains("\"code\":\"agent_not_ready\""), payload);
            assertTrue(payload.contains("Agent is not deployed or not ready"), payload);
            // The exception's own message mirrors what the non-streaming twin
            // withholds behind a bare 404 — it must not leak here either.
            assertFalse(payload.contains("environment=restricted"), payload);
        }

        @Test
        @DisplayName("agent mismatch → the twin's fixed 409 text, not the id-bearing message")
        void agentMismatchUsesFixedText() throws Exception {
            String payload = errorPayloadFor(new IConversationService.AgentMismatchException(
                    "Supplied agentId (agent-7) is incompatible with conversationId (conv-1)"));

            assertTrue(payload.contains("\"code\":\"agent_mismatch\""), payload);
            assertTrue(payload.contains("Agent version mismatch"), payload);
            assertFalse(payload.contains("agent-7"), payload);
        }

        @Test
        @DisplayName("anything else stays opaque: fixed message + correlationId, no code")
        void unknownExceptionsStayOpaque() throws Exception {
            String payload = errorPayloadFor(
                    new RuntimeException("mongodb://replica-set-member:27017 unreachable"));

            assertTrue(payload.contains("Internal server error"), payload);
            assertTrue(payload.contains("correlationId"), payload);
            assertFalse(payload.contains("\"code\""), payload);
            assertFalse(payload.contains("mongodb://"), payload);
        }
    }
}
