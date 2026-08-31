/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.AgentMismatchException;
import ai.labs.eddi.engine.api.IConversationService.AgentNotReadyException;
import ai.labs.eddi.engine.api.IConversationService.ConversationAwaitingApprovalException;
import ai.labs.eddi.engine.api.IConversationService.ConversationEndedException;
import ai.labs.eddi.engine.api.IConversationService.ConversationNotFoundException;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.api.IRestAgentEngineStreaming;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;

import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Audit actor recorded when a turn is cancelled because the SSE client went
     * away.
     */
    static final String CANCELLED_BY_CLIENT_DISCONNECT = "system:client-disconnect";

    /**
     * Default for {@code eddi.streaming.cancel-on-client-disconnect}.
     *
     * @see #cancelOnClientDisconnect
     */
    static final String DEFAULT_CANCEL_ON_CLIENT_DISCONNECT = "true";

    private final IConversationService conversationService;
    private final ConversationAccessGuard conversationAccessGuard;

    /**
     * Whether a vanished SSE client cancels the turn it was streaming
     * ({@code eddi.streaming.cancel-on-client-disconnect}, default {@code true}).
     *
     * <p>
     * <strong>Enabled</strong> (default) stops generation the moment the client is
     * gone, which is the whole point: closing the tab at token 5 of 4000 otherwise
     * still runs — and bills — the full completion and may escalate through every
     * cascade model on the way. The trade-off is that a cancelled turn is
     * DISCARDED, not saved: {@code ConversationService} deliberately skips
     * persistence for a cancelled turn (a partial snapshot must never overwrite a
     * newer or terminal state), so the user's own message and everything produced
     * so far are lost and the conversation settles on
     * {@code EXECUTION_INTERRUPTED}. That state is recoverable — the next
     * {@code say} runs a fresh turn — but the dropped turn has to be re-sent.
     * </p>
     *
     * <p>
     * <strong>Disabled</strong> lets the turn run to completion and persist even
     * though nobody is reading the stream, so a transient drop (proxy idle timeout,
     * a phone switching from Wi-Fi to cellular) costs the generation but never
     * loses the exchange — the client sees the full answer when it reloads the
     * conversation. Deployments on flaky mobile networks, or ones where a lost turn
     * is more expensive than a wasted completion, should turn this off.
     * </p>
     */
    private final boolean cancelOnClientDisconnect;

    @Inject
    public RestAgentEngineStreaming(IConversationService conversationService,
            ConversationAccessGuard conversationAccessGuard,
            @ConfigProperty(name = "eddi.streaming.cancel-on-client-disconnect",
                            defaultValue = DEFAULT_CANCEL_ON_CLIENT_DISCONNECT) boolean cancelOnClientDisconnect) {
        this.conversationService = conversationService;
        this.conversationAccessGuard = conversationAccessGuard;
        this.cancelOnClientDisconnect = cancelOnClientDisconnect;
    }

    /**
     * Convenience constructor for the direct-construction unit tests — applies the
     * shipped default for {@code eddi.streaming.cancel-on-client-disconnect}.
     */
    RestAgentEngineStreaming(IConversationService conversationService,
            ConversationAccessGuard conversationAccessGuard) {
        this(conversationService, conversationAccessGuard, Boolean.parseBoolean(DEFAULT_CANCEL_ON_CLIENT_DISCONNECT));
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
                    new StreamingResponseHandler() {
                        @Override
                        public void onTaskStart(TaskId taskId, String taskType, int index) {
                            stream.send("task_start",
                                    String.format("{\"taskId\":\"%s\",\"taskType\":\"%s\",\"index\":%d}", taskId.getIdentifier(), taskType, index));
                        }

                        @Override
                        public void onToolCall(String toolName) {
                            stream.send("tool_call",
                                    String.format("{\"tool\":\"%s\"}", escapeJson(toolName)));
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
                                stream.send("error", logAndBuildOpaqueErrorEvent(
                                        "Streaming error for conversation " + safeConversationId, error));
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
            stream.send("error", buildKnownConditionOrOpaqueErrorEvent(
                    "Failed to start streaming for conversation " + safeConversationId, e));
            stream.close();
        }
    }

    /**
     * Maps the known client conditions {@code sayStreaming} rejects synchronously
     * to a typed {@code error} event — {@code {"message":…,"code":…}} — instead of
     * the opaque internal-error shape.
     * <p>
     * These are not internal errors: the non-streaming twin
     * ({@code RestAgentEngine}) gives each a proper status (409/410/404/429/403)
     * with a client-safe body, and before this method the SAME condition on the
     * streaming path surfaced as {@code {"message":"Internal server error"}} —
     * observed live when a message was sent into an AWAITING_HUMAN conversation:
     * the backend refused correctly and the client rendered an opaque 500-style
     * blob with no way to react.
     * <p>
     * Per exception, the message mirrors exactly what the twin already discloses —
     * echoed for the conditions whose message is a fixed safe template
     * (awaiting-approval, quota, GDPR restriction), replaced by the twin's fixed
     * text for those whose message names deployment internals (agent-not-ready
     * carries environment and agentId; mismatch carries ids). No new disclosure
     * either way. Everything else stays opaque via
     * {@link #logAndBuildOpaqueErrorEvent}: those paths' messages can name
     * collections, hosts and replica-set members.
     * <p>
     * The {@code code} field is the machine-readable part clients key on —
     * {@code awaiting_approval} is what lets the Manager re-render the approval
     * banner instead of an error blob when input races an undecided pause.
     */
    private String buildKnownConditionOrOpaqueErrorEvent(String context, Exception e) {
        String code;
        String message;
        if (e instanceof ConversationAwaitingApprovalException) {
            code = "awaiting_approval";
            message = e.getMessage();
        } else if (e instanceof ConversationNotFoundException) {
            // The twin answers 404 here. This message is a fixed template carrying
            // only the caller's own (sanitized) conversationId, so it is echoed
            // rather than replaced — no new disclosure.
            code = "conversation_not_found";
            message = e.getMessage();
        } else if (e instanceof ConversationEndedException) {
            code = "conversation_ended";
            message = "Conversation has ended";
        } else if (e instanceof AgentNotReadyException) {
            code = "agent_not_ready";
            message = "Agent is not deployed or not ready";
        } else if (e instanceof AgentMismatchException) {
            code = "agent_mismatch";
            message = "Agent version mismatch";
        } else if (e instanceof QuotaExceededException) {
            code = "quota_exceeded";
            message = e.getMessage();
        } else if (e instanceof ProcessingRestrictedException) {
            code = "processing_restricted";
            message = e.getMessage();
        } else {
            return logAndBuildOpaqueErrorEvent(context, e);
        }
        // WARN, not ERROR with stack trace: the request was rejected by design.
        LOGGER.warnf("%s: %s", context, e.getMessage());
        return String.format("{\"message\":\"%s\",\"code\":\"%s\"}", escapeJson(message), code);
    }

    /**
     * Log the failure detail at ERROR under a fresh correlation id and return the
     * only thing safe to push down the stream: a fixed message plus that id.
     * <p>
     * Same treatment {@code RestAgentManagement.logAndBuildOpaqueMessage} applies
     * to the non-streaming twin, and for the same reason — {@code sayStreaming}
     * loads the conversation snapshot, so sneaky-thrown
     * {@code ResourceStoreException}s reach these catches and their messages name
     * collections, hosts and replica-set members. Echoing them turned any failing
     * stream into deployment reconnaissance, over a channel a browser renders
     * directly.
     *
     * @return the JSON body of the {@code error} SSE event
     */
    private static String logAndBuildOpaqueErrorEvent(String context, Throwable error) {
        String correlationId = UUID.randomUUID().toString();
        LOGGER.errorf(error, "%s [correlationId=%s]: %s", context, correlationId,
                error != null ? error.getMessage() : "null");
        return String.format("{\"message\":\"Internal server error\",\"correlationId\":\"%s\"}", correlationId);
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
     * completion, and could escalate through every cascade model on the way. A
     * cancelled turn is DISCARDED rather than saved — see
     * {@link RestAgentEngineStreaming#cancelOnClientDisconnect} for the full
     * semantics and the config switch that turns this off.
     * <p>
     * The cancel is issued synchronously on the calling (pipeline worker) thread
     * and at most once, so it never touches the Vert.x event loop and never
     * repeats.
     */
    /**
     * Prefix every line of an SSE payload with one space.
     * <p>
     * The SSE grammar is {@code field ":" [ space ] value}, and a consumer strips a
     * single leading space from each {@code data:} line — it cannot tell a
     * separator space from the payload's own first character. RESTEasy Reactive
     * writes {@code data:} with NO separator, so a payload that begins with a space
     * arrives one space short.
     * <p>
     * That is not cosmetic for a token stream. The model emits {@code "-"} then
     * {@code " alpha"}; the client reassembled {@code "-alpha"}, which is no longer
     * a Markdown list item, and words split across tokens ran together ("quota
     * enforcement" → "quotaenforcement"). Whole replies rendered as one mangled
     * paragraph.
     * <p>
     * Padding every line — not just the first — is what makes it correct: RESTEasy
     * emits one {@code data:} line per {@code \n}, so an indented continuation line
     * would otherwise lose a space of its own indentation. The consumer strips
     * exactly the space added here and the payload survives byte for byte.
     * Spec-compliant clients are unaffected by the change; this only makes EDDI's
     * output match what they already assume.
     */
    static String padDataLines(String data) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        // \r is a line break to RESTEasy's SSE serializer too (SseUtil starts a
        // new data: line on either), so a payload with a bare \r would get an
        // UNPADDED continuation line. Normalise \r\n and \r to \n first; the
        // consumer reassembles data lines with \n regardless, so the
        // normalisation is invisible to it.
        String normalised = data.replace("\r\n", "\n").replace('\r', '\n');
        return " " + normalised.replace("\n", "\n ");
    }

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
                eventSink.send(sse.newEventBuilder().name(eventName).data(String.class, padDataLines(data)).build());
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
         * <p>
         * Skipped entirely when {@code eddi.streaming.cancel-on-client-disconnect} is
         * off: the turn then finishes and persists normally, so a transient drop does
         * not cost the user their message. See
         * {@link RestAgentEngineStreaming#cancelOnClientDisconnect} for the trade-off.
         */
        private void onClientGone() {
            if (terminal.get() || !cancelSignalled.compareAndSet(false, true)) {
                return;
            }
            if (!cancelOnClientDisconnect) {
                LOGGER.infof("SSE client disconnected from conversation %s — letting the turn finish and persist "
                        + "(eddi.streaming.cancel-on-client-disconnect is disabled)", safeConversationId);
                return;
            }
            LOGGER.infof("SSE client disconnected from conversation %s — cancelling the in-flight turn; "
                    + "its output is discarded and the conversation settles on EXECUTION_INTERRUPTED",
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

    /**
     * Escapes a string for embedding in the hand-built JSON of an SSE event.
     *
     * <p>
     * The replace-chain this grew from covered {@code \ " \n \r \t} and left every
     * other control character raw, which is invalid inside a JSON string (RFC 8259
     * §7) and makes the event unparseable for a strict client. The values reaching
     * here are not all ours: a tool name comes from an LLM, an error summary from
     * an exception message, and a conversation id straight off the request path.
     * U+2028 and U+2029 are legal JSON but terminate a line in JavaScript, so they
     * are escaped too rather than shipped to a browser.
     * </p>
     */
    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        var sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
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
            // The pause's IDENTITY, not just its existence. A turn may pause up
            // to maxPausesPerTurn times, and hitlPausedAt is the only field
            // that distinguishes one pause from the next — clients compare it
            // to decide whether a decision has been acted on and to key their
            // approval-detail caches. Omitting it here (while the REST snapshot
            // carried it) left streamed pauses identityless: the Manager's
            // settle-poll then read every re-pause as the pause it had already
            // decided and spun to its timeout with the Approve button dead.
            // Instant.toString() is ISO_INSTANT — the same formatter Jackson's
            // JavaTimeModule uses for the REST snapshot, so the two channels
            // stay byte-identical and string comparison across them is sound.
            if (snapshot.getHitlPausedAt() != null) {
                sb.append(",\"hitlPausedAt\":\"").append(snapshot.getHitlPausedAt()).append("\"");
            }
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
