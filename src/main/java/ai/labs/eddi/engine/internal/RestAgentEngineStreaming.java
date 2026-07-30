/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IRestAgentEngineStreaming;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;

import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE streaming implementation — maps ConversationService streaming events to
 * JAX-RS SSE events.
 * <p>
 * Event types:
 * <ul>
 * <li>{@code task_start} — lifecycle task began execution</li>
 * <li>{@code task_complete} — lifecycle task finished</li>
 * <li>{@code token} — LLM response token</li>
 * <li>{@code cascade_step_start} — a multi-model cascade step began
 * ({@code stepIndex}, {@code modelType}, {@code modelName},
 * {@code totalSteps})</li>
 * <li>{@code cascade_escalation} — a cascade step was rejected and escalated
 * ({@code fromStep}, {@code toStep}, {@code confidence}, {@code threshold},
 * {@code reason}, {@code durationMs})</li>
 * <li>{@code done} — full conversation snapshot (final event)</li>
 * <li>{@code error} — error during processing</li>
 * </ul>
 */
@ApplicationScoped
public class RestAgentEngineStreaming implements IRestAgentEngineStreaming {

    private static final Logger LOGGER = Logger.getLogger(RestAgentEngineStreaming.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Audit actor recorded when a turn is cancelled because the SSE client went
     * away.
     */
    static final String CANCELLED_BY_CLIENT_DISCONNECT = "system:client-disconnect";

    private final IConversationService conversationService;
    private final ConversationAccessGuard conversationAccessGuard;

    @Inject
    public RestAgentEngineStreaming(IConversationService conversationService,
            ConversationAccessGuard conversationAccessGuard) {
        this.conversationService = conversationService;
        this.conversationAccessGuard = conversationAccessGuard;
    }

    @Override
    public void sayStreaming(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                             InputData inputData, SseEventSink eventSink, Sse sse) {

        final String safeConversationId = sanitize(conversationId);

        // Same owner-or-admin gate the non-streaming twin applies (RestAgentEngine)
        // — without it, anyone who learns a conversationId could drive a turn under
        // its owner's identity. Runs BEFORE the sink is touched so the denial
        // surfaces as a plain 403 rather than an SSE 'error' event on a 200 stream.
        // ConversationService re-checks: this layer is defence in depth.
        conversationAccessGuard.requireConversationOwner(conversationId);

        // Every outbound frame goes through this stream, which doubles as the
        // client-disconnect detector — see SseStream.
        final SseStream stream = new SseStream(conversationId, safeConversationId, eventSink, sse);

        try {
            conversationService.sayStreaming(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData,
                    new IConversationService.StreamingResponseHandler() {
                        @Override
                        public void onTaskStart(TaskId taskId, String taskType, int index) {
                            stream.send("task_start",
                                    String.format("{\"taskId\":\"%s\",\"taskType\":\"%s\",\"index\":%d}", taskId.getIdentifier(), taskType, index));
                        }

                        @Override
                        public void onTaskComplete(TaskId taskId, String taskType, long durationMs, Map<String, Object> summary) {
                            var sb = new StringBuilder();
                            sb.append(String.format("{\"taskId\":\"%s\",\"taskType\":\"%s\",\"durationMs\":%d", taskId.getIdentifier(), taskType,
                                    durationMs));
                            if (summary.containsKey("actions")) {
                                sb.append(",\"actions\":").append(toJsonArray(summary.get("actions")));
                            }
                            if (summary.containsKey("toolTrace")) {
                                try {
                                    sb.append(",\"toolTrace\":").append(
                                            MAPPER.writeValueAsString(summary.get("toolTrace")));
                                } catch (Exception ex) {
                                    LOGGER.debugf("Failed to serialize toolTrace: %s", ex.getMessage());
                                }
                            }
                            if (summary.containsKey("confidence")) {
                                sb.append(",\"confidence\":").append(summary.get("confidence"));
                            }
                            sb.append("}");
                            stream.send("task_complete", sb.toString());
                        }

                        @Override
                        public void onToken(String token) {
                            stream.send("token", token);
                        }

                        @Override
                        public void onCascadeStepStart(int stepIndex, String modelType, String modelName, int totalSteps) {
                            stream.sendJson("cascade_step_start",
                                    new CascadeStepStartEvent(stepIndex, modelType, modelName, totalSteps));
                        }

                        @Override
                        public void onCascadeEscalation(int fromStep, int toStep, double confidence, double threshold, String reason,
                                                        long durationMs) {
                            stream.sendJson("cascade_escalation",
                                    new CascadeEscalationEvent(fromStep, toStep, finite(confidence), finite(threshold), reason, durationMs));
                        }

                        @Override
                        public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                            stream.markTerminal();
                            try {
                                // Send the final snapshot as JSON
                                stream.send("done", toJson(snapshot));
                            } finally {
                                stream.close();
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            stream.markTerminal();
                            try {
                                LOGGER.errorf("Streaming error for conversation %s: %s", safeConversationId, error.getMessage());
                                stream.send("error", String.format("{\"message\":\"%s\"}", escapeJson(error.getMessage())));
                            } finally {
                                stream.close();
                            }
                        }

                        @Override
                        public void onTaskFailed(TaskId taskId, String taskType, long durationMs,
                                                 String errorType, String errorSummary) {
                            stream.send("task_failed",
                                    String.format("{\"taskId\":\"%s\",\"taskType\":\"%s\",\"durationMs\":%d,\"errorType\":\"%s\",\"error\":\"%s\"}",
                                            escapeJson(taskId.getIdentifier()), escapeJson(taskType), durationMs,
                                            escapeJson(errorType), escapeJson(errorSummary)));
                        }
                    });
        } catch (Exception e) {
            stream.markTerminal();
            LOGGER.errorf("Failed to start streaming for conversation %s: %s", safeConversationId, e.getMessage());
            stream.send("error", String.format("{\"message\":\"%s\"}", escapeJson(e.getMessage())));
            stream.close();
        }
    }

    /**
     * Per-request SSE stream: it owns the sink and, crucially, notices when the
     * client is gone.
     * <p>
     * <strong>Why the send path is the disconnect detector.</strong> The endpoint
     * receives only {@link SseEventSink} and {@link Sse}. A JAX-RS
     * {@code ConnectionCallback} is not an option here: RESTEasy Reactive's
     * {@code AsyncResponseImpl.register} stores connection callbacks in a request
     * property that <em>nothing in the server ever reads</em>, so registering one
     * would be dead code that silently cancels nothing. What IS reliable is
     * {@code SseEventSink.isClosed()} — RESTEasy Reactive implements it as
     * {@code serverResponse().closed()}, which Vert.x flips as soon as the client
     * drops the connection. Every outbound frame therefore re-checks it, which
     * makes a disconnect observable at the very next token/task boundary.
     * <p>
     * On the first such observation before the terminal frame, the in-flight turn
     * is cancelled through {@code IConversationService.cancelConversation}, which
     * sets the cooperative cancel flag on the live conversation memory. Without it,
     * closing the tab at token 5 of 4000 still ran — and billed — the whole
     * completion, and could escalate through every cascade model on the way.
     * <p>
     * The cancel is issued synchronously on the calling (pipeline worker) thread
     * and at most once, so it never touches the Vert.x event loop and never
     * repeats.
     */
    private final class SseStream {
        private final String conversationId;
        private final String safeConversationId;
        private final SseEventSink eventSink;
        private final Sse sse;
        /**
         * Set once the terminal ({@code done}/{@code error}) frame is being emitted.
         */
        private final AtomicBoolean terminal = new AtomicBoolean();
        /** Guarantees the disconnect cancel is signalled at most once per stream. */
        private final AtomicBoolean cancelSignalled = new AtomicBoolean();

        private SseStream(String conversationId, String safeConversationId, SseEventSink eventSink, Sse sse) {
            this.conversationId = conversationId;
            this.safeConversationId = safeConversationId;
            this.eventSink = eventSink;
            this.sse = sse;
        }

        /**
         * Marks the stream as finishing normally, so the sink closing from here on is
         * our own doing and must not be mistaken for a client disconnect.
         */
        void markTerminal() {
            terminal.set(true);
        }

        void send(String eventName, String data) {
            if (eventSink.isClosed()) {
                LOGGER.debugf("SSE sink closed, dropping event: %s", eventName);
                onClientGone();
                return;
            }
            try {
                eventSink.send(sse.newEventBuilder().name(eventName).data(String.class, data).build());
            } catch (Exception e) {
                LOGGER.warnf("Failed to send SSE event '%s': %s", eventName, e.getMessage());
                // RESTEasy Reactive throws IllegalStateException synchronously when the
                // sink closed between the check above and the write. Re-checking the
                // sink (rather than treating every send failure as a disconnect) keeps
                // an unrelated failure — a broken event payload, say — from cancelling
                // a turn whose client is still connected and waiting.
                if (eventSink.isClosed()) {
                    onClientGone();
                }
            }
        }

        /**
         * Serialize a typed event payload to JSON via Jackson and send it. Preferred
         * over hand-built JSON strings — the mapper handles string escaping and number
         * formatting. Falls back to an empty object on the (unexpected) serialization
         * failure so a single bad payload cannot break the stream.
         */
        void sendJson(String eventName, Object payload) {
            String data;
            try {
                data = MAPPER.writeValueAsString(payload);
            } catch (Exception e) {
                LOGGER.warnf("Failed to serialize '%s' event payload: %s", eventName, e.getMessage());
                data = "{}";
            }
            send(eventName, data);
        }

        void close() {
            try {
                if (!eventSink.isClosed()) {
                    eventSink.close();
                }
            } catch (Exception e) {
                LOGGER.debugf("Error closing SSE sink: %s", e.getMessage());
            }
        }

        /**
         * The client is no longer reading this stream. Cancel the turn it was waiting
         * on — once, and never for a stream that already delivered its terminal frame.
         */
        private void onClientGone() {
            if (terminal.get() || !cancelSignalled.compareAndSet(false, true)) {
                return;
            }
            LOGGER.infof("SSE client disconnected from conversation %s — cancelling the in-flight turn",
                    safeConversationId);
            try {
                conversationService.cancelConversation(conversationId, ControlSignal.CANCEL_GRACEFUL,
                        CANCELLED_BY_CLIENT_DISCONNECT);
            } catch (Exception e) {
                LOGGER.warnf("Failed to cancel conversation %s after client disconnect: %s",
                        safeConversationId, e.getMessage());
            }
        }
    }

    /** Typed payload for the {@code cascade_step_start} SSE event. */
    private record CascadeStepStartEvent(int stepIndex, String modelType, String modelName, int totalSteps) {
    }

    /** Typed payload for the {@code cascade_escalation} SSE event. */
    private record CascadeEscalationEvent(int fromStep, int toStep, double confidence, double threshold, String reason, long durationMs) {
    }

    /**
     * Coerce a non-finite double (NaN/Infinity) to 0.0 so it serializes as valid
     * JSON.
     */
    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    private String escapeJson(String text) {
        if (text == null)
            return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String toJsonArray(Object obj) {
        if (obj instanceof List<?> list) {
            var sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0)
                    sb.append(",");
                sb.append("\"").append(escapeJson(String.valueOf(list.get(i)))).append("\"");
            }
            sb.append("]");
            return sb.toString();
        }
        return "[]";
    }

    private String toJson(SimpleConversationMemorySnapshot snapshot) {
        // Simplified JSON for the done event — the full snapshot is available
        // via the standard GET endpoint. We include the essential fields.
        try {
            var sb = new StringBuilder("{");
            sb.append("\"conversationState\":\"").append(snapshot.getConversationState()).append("\"");
            if (snapshot.getConversationOutputs() != null) {
                sb.append(",\"conversationOutputs\":")
                        .append(MAPPER.writeValueAsString(snapshot.getConversationOutputs()));
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            LOGGER.warnf("Failed to serialize snapshot: %s", e.getMessage());
            return "{\"conversationState\":\"" + snapshot.getConversationState() + "\"}";
        }
    }
}
