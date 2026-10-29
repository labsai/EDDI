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
import ai.labs.eddi.engine.api.IConversationService.InputTooLargeException;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.api.IRestAgentEngineStreaming;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictionUnavailableException;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictionUnavailableExceptionMapper;
import ai.labs.eddi.engine.exception.InputTooLargeExceptionMapper;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.rest.QuotaAccountingUnavailableExceptionMapper;

import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.TurnError;
import ai.labs.eddi.engine.security.ClientContextGuard;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
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

    /**
     * Default for {@code eddi.streaming.keepalive-interval-seconds}.
     *
     * @see #keepAliveIntervalSeconds
     */
    static final int DEFAULT_KEEPALIVE_INTERVAL = 15;

    /** {@link #DEFAULT_KEEPALIVE_INTERVAL} as the config annotation needs it. */
    static final String DEFAULT_KEEPALIVE_INTERVAL_SECONDS = "" + DEFAULT_KEEPALIVE_INTERVAL;

    /** The SSE comment text of a keep-alive frame ({@code : keep-alive}). */
    static final String KEEPALIVE_COMMENT = "keep-alive";

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

    /**
     * How long a turn's stream may stay silent before EDDI writes an SSE comment
     * line ({@code : keep-alive}) on it
     * ({@code eddi.streaming.keepalive-interval-seconds}, default 15; {@code 0}
     * turns it off).
     *
     * <p>
     * A turn can go a minute without a single event: a model working out a tool
     * call or a structured reply emits no tokens until it is done. Load balancers
     * and proxies close a connection that carries no bytes for their idle timeout
     * (60 s on an AWS ALB by default), and with {@code cancel-on-client-disconnect}
     * on, that close also discards the turn. The comment line keeps bytes moving;
     * SSE clients ignore comments by definition, so no consumer has to know about
     * it. It is only written when nothing else was sent for the whole interval, so
     * a stream of tokens carries no extra frames. Once the turn has written an
     * event of its own, a tick that finds the sink closed is treated like any other
     * send to a vanished client, so a dropped connection is noticed during the
     * silent stretch too. Before that the turn may still be queued behind another
     * turn of the same conversation, so the tick only stops; the turn's own first
     * event handles the disconnect, as it always did.
     * </p>
     */
    private final int keepAliveIntervalSeconds;

    /**
     * One daemon thread ticks every open turn stream. A tick only checks a clock
     * and, rarely, writes a comment line, so one thread serves any number of
     * streams. Replaceable in unit tests, which run the tick by hand.
     */
    ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "eddi-sse-keepalive");
        t.setDaemon(true);
        return t;
    });

    /** Monotonic clock in milliseconds; replaceable in unit tests. */
    LongSupplier clockMillis = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

    /**
     * Runs the disconnect handling a keep-alive tick discovers. Cancelling a turn
     * reads and writes the store, so it must not run on the one tick thread every
     * stream shares; a virtual thread per (rare) disconnect keeps the ticks on
     * time. Replaceable in unit tests.
     */
    Executor clientGoneExecutor = runnable -> Thread.ofVirtual().name("eddi-sse-client-gone").start(runnable);

    /**
     * Removes the engine-reserved keys from client-supplied context. Field-injected
     * with the strict default so directly constructed unit tests keep it non-null
     * (CDI overwrites it with the configured bean in production).
     */
    @Inject
    ClientContextGuard clientContextGuard = ClientContextGuard.strict();

    /** Reads {@code X-EDDI-Turn-Deadline-Ms}; nullable in unit tests. */
    @Inject
    TurnDeadlineHeaderReader turnDeadlineHeaderReader;

    /**
     * Reads {@code Idempotency-Key} / {@code X-EDDI-Request-Id}; nullable in unit
     * tests.
     */
    @Inject
    IdempotencyKeyHeaderReader idempotencyKeyHeaderReader;

    @Inject
    public RestAgentEngineStreaming(IConversationService conversationService,
            ConversationAccessGuard conversationAccessGuard,
            @ConfigProperty(name = "eddi.streaming.cancel-on-client-disconnect",
                            defaultValue = DEFAULT_CANCEL_ON_CLIENT_DISCONNECT) boolean cancelOnClientDisconnect,
            @ConfigProperty(name = "eddi.streaming.keepalive-interval-seconds",
                            defaultValue = DEFAULT_KEEPALIVE_INTERVAL_SECONDS) int keepAliveIntervalSeconds) {
        this.conversationService = conversationService;
        this.conversationAccessGuard = conversationAccessGuard;
        this.cancelOnClientDisconnect = cancelOnClientDisconnect;
        this.keepAliveIntervalSeconds = Math.max(0, keepAliveIntervalSeconds);
    }

    /**
     * Convenience constructor for the direct-construction unit tests — applies the
     * shipped default for {@code eddi.streaming.keepalive-interval-seconds}.
     */
    RestAgentEngineStreaming(IConversationService conversationService,
            ConversationAccessGuard conversationAccessGuard, boolean cancelOnClientDisconnect) {
        this(conversationService, conversationAccessGuard, cancelOnClientDisconnect,
                DEFAULT_KEEPALIVE_INTERVAL);
    }

    /**
     * Convenience constructor for the direct-construction unit tests — applies the
     * shipped defaults for both streaming settings.
     */
    RestAgentEngineStreaming(IConversationService conversationService,
            ConversationAccessGuard conversationAccessGuard) {
        this(conversationService, conversationAccessGuard, Boolean.parseBoolean(DEFAULT_CANCEL_ON_CLIENT_DISCONNECT));
    }

    @PreDestroy
    void shutdownKeepAlive() {
        keepAliveScheduler.shutdownNow();
    }

    @Override
    public void sayStreaming(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                             InputData inputData, SseEventSink eventSink, Sse sse) {

        final String safeConversationId = sanitize(conversationId);

        // A node at capacity answers a plain 503 before the sink is touched and
        // before the ownership check reads the database for a turn it will refuse.
        conversationService.refuseIfAtCapacity(conversationId);

        // Same owner-or-admin gate the non-streaming twin applies (RestAgentEngine)
        // — without it, anyone who learns a conversationId could drive a turn under
        // its owner's identity. Runs BEFORE the sink is touched so the denial
        // surfaces as a plain 403 rather than an SSE 'error' event on a 200 stream.
        // ConversationService re-checks: this layer is defence in depth.
        // Owner or admin, plus the agent USE re-check every turn gets (L4).
        conversationAccessGuard.requireConversationTurnAccess(conversationId);

        // The input cap, before the sink for the same reason: an oversized input is the
        // 413 InputTooLargeExceptionMapper answers, not an SSE 'error' event on a 200
        // stream. ConversationService re-checks, and the catch below still maps it.
        conversationService.requireInputWithinLimit(inputData);

        // Engine-reserved context keys are only ever set by EDDI itself — see
        // ClientContextGuard. Same boundary as the non-streaming twin.
        clientContextGuard.strip(inputData);
        if (turnDeadlineHeaderReader != null) {
            turnDeadlineHeaderReader.apply(inputData);
        }
        if (idempotencyKeyHeaderReader != null) {
            try {
                idempotencyKeyHeaderReader.apply(inputData);
            } catch (TurnIdempotencyService.InvalidIdempotencyKeyException e) {
                // Before the sink is touched, like the input cap: a plain 400, not an
                // 'error' event on a 200 stream.
                throw new BadRequestException(e.getMessage(), Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.APPLICATION_JSON).entity(RestAgentEngine.errorBody(
                                new TurnError("INVALID_IDEMPOTENCY_KEY", false, null, e.getMessage())))
                        .build());
            }
        }

        // Every outbound frame goes through this stream, which doubles as the
        // client-disconnect detector — see SseStream.
        final SseStream stream = new SseStream(conversationId, safeConversationId, eventSink, sse);
        stream.startKeepAlive();

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
                        public void onLlmRetry(String reason, int attempt) {
                            stream.sendJson("llm_retry", new LlmRetryEvent(reason, attempt));
                        }

                        @Override
                        public void onGuardrailTriggered(String type, String phase, String action, String taskId) {
                            stream.sendJson("guardrail_triggered", new GuardrailTriggeredEvent(type, phase, action, taskId));
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
     * ({@code RestAgentEngine}) gives each a proper status
     * (409/410/404/429/403/503) with a client-safe body, and before this method the
     * SAME condition on the streaming path surfaced as {@code {"message":"Internal
     * server error"}} — observed live when a message was sent into an
     * AWAITING_HUMAN conversation: the backend refused correctly and the client
     * rendered an opaque 500-style blob with no way to react.
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
        } else if (e instanceof InputTooLargeException) {
            // The twin answers 413. The message names only the length and the
            // documented limit, so it is echoed.
            code = InputTooLargeExceptionMapper.ERROR_CODE;
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
        } else if (e instanceof QuotaAccountingUnavailableException quotaUnavailable) {
            // Before QuotaExceededException is irrelevant (they are unrelated types),
            // but the distinction is the same one RestAgentEngine draws: the store
            // could not answer, so this is not the tenant being over a limit. The
            // message is this class's own fixed text and names nothing internal, so
            // it is echoed rather than replaced — through the mapper's accessor, so a
            // thrower that supplies no message produces the same sentence here as on
            // the other two surfaces instead of "message":"".
            code = "quota_accounting_unavailable";
            message = QuotaAccountingUnavailableExceptionMapper.messageOf(quotaUnavailable);
        } else if (e instanceof QuotaExceededException) {
            code = "quota_exceeded";
            message = e.getMessage();
        } else if (e instanceof ProcessingRestrictedException) {
            code = "processing_restricted";
            message = e.getMessage();
        } else if (e instanceof ProcessingRestrictionUnavailableException restrictionUnavailable) {
            // The twin answers 503 restriction_status_unavailable. Without this
            // branch a store failover reached the client as
            // {"message":"Internal server error"} on every streamed turn, with an
            // ERROR stack trace per turn behind it — the honest-503 fix had landed on
            // the synchronous start endpoint only. The message is a fixed template
            // naming no deployment internals, so it is echoed rather than replaced —
            // through the mapper's accessor, so a thrower that supplies no message
            // produces the same sentence here as on the other two surfaces instead of
            // an empty one.
            code = "restriction_status_unavailable";
            message = ProcessingRestrictionUnavailableExceptionMapper.messageOf(restrictionUnavailable);
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
        /**
         * Serialises writes to the sink: the turn's events and the keep-alive tick come
         * from different threads, and a comment line must never land between two lines
         * of an event.
         */
        private final Object writeLock = new Object();
        /** When the last frame was written; guarded by {@link #writeLock}. */
        private long lastWriteMillis;
        private volatile ScheduledFuture<?> keepAlive;
        /**
         * Set once one of this turn's own events was written, which proves the turn is
         * the one running. Until then it may still be queued behind another turn of the
         * same conversation, and a conversation-wide cancel would interrupt that other
         * turn instead.
         */
        private volatile boolean turnEventSent;

        private SseStream(String conversationId, String safeConversationId, SseEventSink eventSink, Sse sse) {
            this.conversationId = conversationId;
            this.safeConversationId = safeConversationId;
            this.eventSink = eventSink;
            this.sse = sse;
            this.lastWriteMillis = clockMillis.getAsLong();
        }

        /**
         * Starts the keep-alive tick for this stream, unless it is turned off. The tick
         * runs at the interval and writes a comment only when the stream has been
         * silent for at least one whole interval.
         */
        void startKeepAlive() {
            if (keepAliveIntervalSeconds <= 0) {
                return;
            }
            try {
                // A tick only writes after a whole silent interval, so ticking once per
                // interval could leave a gap of almost two. A quarter interval caps it at
                // about 1.25.
                long tickMillis = Math.max(1L, TimeUnit.SECONDS.toMillis(keepAliveIntervalSeconds) / 4);
                keepAlive = keepAliveScheduler.scheduleAtFixedRate(this::keepAliveTick, tickMillis, tickMillis,
                        TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // A rejected schedule (shutting down) must not fail the turn.
                LOGGER.debugf("SSE keep-alive not scheduled for conversation %s: %s", safeConversationId, e.getMessage());
            }
        }

        void keepAliveTick() {
            if (terminal.get()) {
                stopKeepAlive();
                return;
            }
            boolean gone = false;
            synchronized (writeLock) {
                if (clockMillis.getAsLong() - lastWriteMillis < TimeUnit.SECONDS.toMillis(keepAliveIntervalSeconds)) {
                    return;
                }
                if (eventSink.isClosed()) {
                    gone = true;
                } else {
                    try {
                        eventSink.send(sse.newEventBuilder().comment(KEEPALIVE_COMMENT).build());
                        lastWriteMillis = clockMillis.getAsLong();
                    } catch (Exception e) {
                        LOGGER.debugf("Failed to send SSE keep-alive for conversation %s: %s", safeConversationId,
                                e.getMessage());
                        gone = eventSink.isClosed();
                    }
                }
            }
            if (gone) {
                stopKeepAlive();
                // Only a turn that has started may be cancelled from here: a stream still
                // queued behind another turn of the same conversation would otherwise
                // interrupt that turn. A queued turn's own first event finds the sink
                // closed and cancels it then, as before.
                if (turnEventSent) {
                    clientGoneExecutor.execute(this::onClientGone);
                }
            }
        }

        private void stopKeepAlive() {
            ScheduledFuture<?> future = keepAlive;
            if (future != null) {
                future.cancel(false);
            }
        }

        /**
         * Marks the stream as finishing normally, so the sink closing from here on is
         * our own doing and must not be mistaken for a client disconnect.
         */
        void markTerminal() {
            terminal.set(true);
        }

        void send(String eventName, String data) {
            boolean gone = false;
            synchronized (writeLock) {
                if (eventSink.isClosed()) {
                    LOGGER.debugf("SSE sink closed, dropping event: %s", eventName);
                    gone = true;
                } else {
                    try {
                        eventSink.send(sse.newEventBuilder().name(eventName).data(String.class, padDataLines(data)).build());
                        lastWriteMillis = clockMillis.getAsLong();
                        turnEventSent = true;
                    } catch (Exception e) {
                        LOGGER.warnf("Failed to send SSE event '%s': %s", eventName, e.getMessage());
                        // RESTEasy Reactive throws IllegalStateException synchronously when the
                        // sink closed between the check above and the write. Re-checking the
                        // sink (rather than treating every send failure as a disconnect) keeps
                        // an unrelated failure — a broken event payload, say — from cancelling
                        // a turn whose client is still connected and waiting.
                        gone = eventSink.isClosed();
                    }
                }
            }
            // Outside the lock: the cancel can call back into this stream from
            // another thread.
            if (gone) {
                onClientGone();
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
            stopKeepAlive();
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
            stopKeepAlive();
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

    /** Typed payload for the {@code llm_retry} SSE event. */
    private record LlmRetryEvent(String reason, int attempt) {
    }

    /** Typed payload for the {@code guardrail_triggered} SSE event. */
    private record GuardrailTriggeredEvent(String type, String phase, String action, String taskId) {
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
            if (snapshot.getError() != null) {
                sb.append(",\"error\":").append(MAPPER.writeValueAsString(snapshot.getError()));
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            LOGGER.warnf("Failed to serialize snapshot: %s", e.getMessage());
            return "{\"conversationState\":\"" + snapshot.getConversationState() + "\"}";
        }
    }
}
