/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Adapts a {@link StreamingChatModel} to the synchronous {@link ChatModel}
 * contract, forwarding partial text tokens to the conversation's
 * {@link ConversationEventSink} as they arrive — the one place the streaming
 * contract is applied to a model call.
 * <p>
 * Every streaming path runs its model calls through it: the tool loop
 * ({@link ToolLoopRunner}), the no-tools streaming path (inside
 * {@link LegacyChatExecutor}, see {@link StreamingLegacyChatExecutor}) and a
 * cascade's live final step. The caller stays synchronous and keeps its retry,
 * budget, approval-gate and pause/resume semantics, because from its point of
 * view this is just a {@code ChatModel}; retries come from
 * {@code RetryConfiguration.executeWithRetry} — classified, honouring
 * {@code Retry-After} and the turn deadline — exactly as for a non-streaming
 * call.
 * <p>
 * <strong>The contract</strong> (see {@link StreamPolicy}): a call succeeds
 * only when the provider completes it with a finish reason and no error; the
 * first terminal callback ends it; the wall-clock backstop, the idle limit and
 * the turn's cancel flag are enforced while waiting, and each stops the
 * provider's stream. A completion without a finish reason (a connection that
 * simply closed) is an {@link IncompleteStreamException} — retryable while
 * nothing was sent.
 * <p>
 * <strong>Rounds that carry an API-level JSON response format are not
 * forwarded</strong> — partial JSON is useless to render — but still execute
 * over the streaming transport and return the complete response. The caller
 * decides whether the final text still needs the single-chunk fallback emit by
 * comparing against {@link #lastForwardedText()}.
 * <p>
 * <strong>A call that fails after forwarding text is not retried.</strong> A
 * retry streams the call again from its start into the same sink, so the client
 * would render the failed attempt's fragment glued to the retry's full answer.
 * Tokens cannot be taken back, so such a failure is raised as a
 * {@link PartiallyStreamedRoundException}, which the retry classifies as
 * non-retryable, and the text that reached the client is handed to the policy's
 * partial-text sink as error detail. A call that failed before forwarding
 * anything is retried as before.
 */
class ToolLoopStreamingChatModel implements ChatModel {
    private static final Logger LOGGER = Logger.getLogger(ToolLoopStreamingChatModel.class);

    /** The {@code path} tag of this bridge's stream metrics in the tool loop. */
    static final String PATH_TOOL_LOOP = "tool_loop";

    /**
     * A model call that failed after part of its text had reached the client.
     * Deliberately carries no cause and no provider wording: the retry classifies a
     * failure by its cause chain and message, and this one must read as
     * non-retryable whatever the provider said. The provider's failure is attached
     * as a suppressed exception (not walked by the classifier) and logged.
     * <p>
     * The message is a fixed string for the same reason. It used to carry the
     * number of characters forwarded, and the classifier's last-resort wording test
     * reads a standalone {@code 429}, {@code 502}, {@code 503} or {@code 504} as a
     * transient HTTP status: a round that failed after exactly 503 characters was
     * retried into the same sink. The count and the model type go to the log.
     * <p>
     * The forwarded text travels in {@link #partialText()}, never in the message:
     * the message reaches the failure digest a client sees.
     */
    static final class PartiallyStreamedRoundException extends RuntimeException {
        static final String MESSAGE = "Streaming model round failed after part of its text had been sent to the client; "
                + "not retried, a retry would send that text again";

        private final transient String partialText;
        private final boolean timedOut;

        PartiallyStreamedRoundException(Throwable providerFailure) {
            this(providerFailure, "", false);
        }

        PartiallyStreamedRoundException(Throwable providerFailure, String partialText, boolean timedOut) {
            super(MESSAGE);
            this.partialText = partialText != null ? partialText : "";
            this.timedOut = timedOut;
            if (providerFailure != null) {
                addSuppressed(providerFailure);
            }
        }

        /** Exactly the text the client was sent before the failure. */
        String partialText() {
            return partialText;
        }

        /** Whether the call was given up on (backstop or idle gap), not failed. */
        boolean timedOut() {
            return timedOut;
        }
    }

    /**
     * The provider completed the stream without a finish reason — almost always a
     * connection that closed mid-answer, reported by the client library as a normal
     * end of stream. A {@link RetriableException}, so a call that sent nothing yet
     * is retried like any transient transport failure.
     */
    static final class IncompleteStreamException extends RetriableException {
        IncompleteStreamException(String modelType) {
            super("[" + modelType + "] The model's stream ended without a finish reason; the answer is incomplete");
        }
    }

    /**
     * The turn was cancelled ({@code /cancel}, or the client disconnected with
     * cancel-on-disconnect) while the call ran. Not retryable: the classifier finds
     * nothing transient in it.
     */
    static final class StreamCancelledException extends RuntimeException {
        StreamCancelledException(String modelType) {
            super("[" + modelType + "] Streaming model call stopped: the turn was cancelled");
        }
    }

    private final StreamingChatModel delegate;
    private final ConversationEventSink eventSink;
    private final StreamPolicy policy;
    private final String modelType;
    private final String metricPath;

    /**
     * Exactly the text forwarded to the sink by the most recent completed call.
     * Written only by {@link #chat} after its call has completed; the caller is
     * single-threaded per conversation turn.
     */
    private volatile String lastForwardedText = "";

    /** The finish reason of the most recent completed call. */
    private volatile FinishReason lastFinishReason;

    /**
     * Whether ANY earlier round of this turn forwarded text — the trigger for the
     * inter-round separator. Without it, interim commentary and the final answer
     * run together on the live stream ("Let me check…There are 3"). The separator
     * goes to the sink only, never into the per-call forwarded record, so the
     * caller's exact-match suppression still compares pure round text. Written only
     * by a live round's token forwarder, under that round's lock.
     */
    private volatile boolean anyRoundForwarded;

    /** A bridge with only a wall-clock backstop and the deployment defaults. */
    ToolLoopStreamingChatModel(StreamingChatModel delegate, ConversationEventSink eventSink, long timeoutSeconds, String modelType) {
        this(delegate, eventSink, StreamPolicy.ofBackstop(timeoutSeconds), modelType, PATH_TOOL_LOOP);
    }

    ToolLoopStreamingChatModel(StreamingChatModel delegate, ConversationEventSink eventSink, StreamPolicy policy, String modelType,
            String metricPath) {
        this.delegate = delegate;
        this.eventSink = eventSink;
        this.policy = policy;
        this.modelType = modelType;
        this.metricPath = metricPath;
    }

    /**
     * The text token-forwarded by the last completed model call — {@code ""} when
     * that call forwarded nothing (JSON format, buffered provider, or a pure
     * tool-call round). Callers compare this against the turn's final response text
     * to decide whether the single-chunk fallback emit is still required.
     */
    String lastForwardedText() {
        return lastForwardedText;
    }

    /** The finish reason of the last completed call, or {@code null}. */
    FinishReason lastFinishReason() {
        return lastFinishReason;
    }

    /**
     * Records the last completed call's finish reason in {@code metadata} — as
     * {@code finishReason}, plus the {@code warning} ({@code truncated} or
     * {@code content_filter}) responseValidation and the turn's outcome key on. An
     * existing warning is not replaced.
     */
    void recordLastFinish(Map<String, Object> metadata) {
        putFinishReason(metadata, lastFinishReason);
    }

    static void putFinishReason(Map<String, Object> metadata, FinishReason finishReason) {
        if (metadata == null || finishReason == null) {
            return;
        }
        metadata.put("finishReason", finishReason.toString());
        if (finishReason == FinishReason.LENGTH) {
            metadata.putIfAbsent("warning", "truncated");
        } else if (finishReason == FinishReason.CONTENT_FILTER) {
            metadata.putIfAbsent("warning", "content_filter");
        }
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        // Partial JSON is not renderable — run over the streaming transport but
        // do not forward tokens for this round.
        var call = chatRequest.responseFormat() == null
                ? AbandonableStreamCall.forwardingTo(this::forwardToken)
                : AbandonableStreamCall.discardingPartials();
        delegate.chat(chatRequest, call);

        AbandonableStreamCall.Outcome outcome;
        try {
            outcome = call.await(policy);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reportPartial(call);
            throw new RuntimeException(String.format("[%s] Streaming chat request interrupted", modelType), e);
        }

        switch (outcome) {
            case COMPLETED -> {
                ChatResponse response = call.response();
                FinishReason finishReason = response != null && response.metadata() != null ? response.metadata().finishReason() : null;
                if (finishReason == null && policy.requireFinishReason()) {
                    count("eddi.llm.stream.incomplete");
                    throw partiallyStreamed(call, new IncompleteStreamException(modelType), false);
                }
                lastForwardedText = call.forwardedText();
                lastFinishReason = finishReason;
                return response;
            }
            case FAILED -> {
                Throwable error = call.error();
                throw partiallyStreamed(call, error instanceof RuntimeException re ? re : new RuntimeException(error), false);
            }
            case TIMED_OUT -> {
                // Counted here, when EDDI gives up (L3). This class is not CDI-managed,
                // hence the global registry (the idiom ToolContextBudget and
                // LifecycleManager use).
                count("eddi.llm.stream.timeouts");
                // Same shape as ObservableChatModel's timeout — including the typed
                // TimeoutException CAUSE the classifier keys on, so a timeout before the
                // first token is retried like on the synchronous path.
                RuntimeException timeout = new RuntimeException(
                        String.format("[%s] Chat request timed out after %dms", modelType, policy.backstopSeconds() * 1000),
                        new TimeoutException("streaming chat exceeded " + policy.backstopSeconds() + "s backstop"));
                throw partiallyStreamed(call, timeout, true);
            }
            case IDLE_TIMEOUT -> {
                count("eddi.llm.stream.idle_timeouts");
                RuntimeException idle = new RuntimeException(
                        String.format("[%s] Streaming chat went silent for %ds", modelType, policy.idleTimeoutSeconds()),
                        new TimeoutException("no stream event for " + policy.idleTimeoutSeconds() + "s"));
                throw partiallyStreamed(call, idle, true);
            }
            default -> {
                countCancelled();
                LOGGER.infof("[%s] Streaming model call stopped because the turn was cancelled (%d characters sent, provider stream %s)",
                        modelType, call.forwardedText().length(), call.cancellable() ? "cancelled" : "left to finish unread");
                reportPartial(call);
                throw new StreamCancelledException(modelType);
            }
        }
    }

    /**
     * Forward one partial token of a live round, preceded by the inter-round
     * separator when it is the round's first and an earlier round already forwarded
     * text. Runs under the round's lock.
     */
    private void forwardToken(String token, boolean firstOfRound) {
        try {
            if (firstOfRound && anyRoundForwarded) {
                eventSink.onToken("\n\n");
            }
            eventSink.onToken(token);
        } catch (Exception e) {
            LOGGER.debugf("Error sending token event: %s", e.getMessage());
        }
        anyRoundForwarded = true;
    }

    /**
     * {@code failure} as it should leave this call: unchanged when nothing was
     * forwarded (the retry may run the call again), otherwise a
     * {@link PartiallyStreamedRoundException} the retry will not repeat, with the
     * forwarded text reported as error detail.
     */
    private RuntimeException partiallyStreamed(AbandonableStreamCall call, RuntimeException failure, boolean timedOut) {
        String text = call.forwardedText();
        if (text.isEmpty()) {
            return failure;
        }
        LOGGER.warnf("[%s] Streaming model round failed after %d characters were sent to the client; not retrying it: %s", modelType,
                text.length(), failure.getMessage());
        policy.partialTextSink().accept(text);
        return new PartiallyStreamedRoundException(failure, text, timedOut);
    }

    private void reportPartial(AbandonableStreamCall call) {
        String text = call.forwardedText();
        if (!text.isEmpty()) {
            policy.partialTextSink().accept(text);
        }
    }

    private void count(String name) {
        try {
            Metrics.globalRegistry.counter(name, "path", metricPath).increment();
        } catch (RuntimeException e) {
            LOGGER.debugf("%s metric emit failed: %s", name, e.getMessage());
        }
    }

    private void countCancelled() {
        try {
            Metrics.globalRegistry.counter("eddi.llm.cancelled", "scope", "stream").increment();
        } catch (RuntimeException e) {
            LOGGER.debugf("stream-cancel metric emit failed: %s", e.getMessage());
        }
    }
}
