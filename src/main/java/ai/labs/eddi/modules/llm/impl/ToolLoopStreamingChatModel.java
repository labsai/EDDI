/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts a {@link StreamingChatModel} to the synchronous {@link ChatModel}
 * contract the tool loop consumes, forwarding partial text tokens to the
 * conversation's {@link ConversationEventSink} as they arrive.
 * <p>
 * This is what makes tool-enabled turns stream token-by-token: the loop in
 * {@link ToolLoopRunner} stays synchronous and keeps its retry, budget,
 * approval-gate and pause/resume semantics untouched, because from its point of
 * view this is just a {@code ChatModel}. Only the transport underneath changed.
 * Tool-call rounds naturally emit little or no text (tool-call deltas are not
 * text partials), so what the client sees live is any interim commentary plus
 * the final answer as the model produces it.
 * <p>
 * <strong>Rounds that carry an API-level JSON response format are not
 * forwarded</strong> — partial JSON is useless to render — but still execute
 * over the streaming transport and return the complete response. The caller
 * decides whether the final text still needs the single-chunk fallback emit by
 * comparing against {@link #lastForwardedText()}; an exact-match comparison,
 * not a boolean, because the loop's last model round is not always the source
 * of the final response (an exhausted iteration budget substitutes a synthetic
 * message) and a JSON round or a buffered provider may forward nothing.
 * <p>
 * Concurrency mirrors {@code StreamingLegacyChatExecutor}: one latch per call,
 * an {@code abandoned} gate plus lock so a timed-out attempt's late tokens can
 * never interleave with a retry's output on the shared sink.
 * <p>
 * <strong>A round that fails after forwarding text is not retried.</strong> The
 * tool loop retries a failed model round, and a retry streams the round again
 * from its start into the same sink — the client rendered the failed attempt's
 * fragment glued to the retry's full answer (the cascade solved the same
 * problem for its steps by streaming only the last one, finding F9). Tokens
 * cannot be taken back, so such a failure is raised as a
 * {@link PartiallyStreamedRoundException}, which the retry classifies as
 * non-retryable; a round that failed before forwarding anything is retried as
 * before. Earlier rounds are never re-streamed (the loop no longer replays
 * whole attempts), and memory is unaffected — it stores only the final returned
 * text.
 */
class ToolLoopStreamingChatModel implements ChatModel {
    private static final Logger LOGGER = Logger.getLogger(ToolLoopStreamingChatModel.class);

    /**
     * A model round that failed after part of its text had reached the client.
     * Deliberately carries no cause and no provider wording: the retry classifies a
     * failure by its cause chain and message, and this one must read as
     * non-retryable whatever the provider said. The provider's failure is attached
     * as a suppressed exception (not walked by the classifier) and logged.
     */
    static final class PartiallyStreamedRoundException extends RuntimeException {
        PartiallyStreamedRoundException(String modelType, int forwardedChars, Throwable providerFailure) {
            super(String.format("[%s] Streaming model round failed after %d characters had been sent to the client; "
                    + "not retried, a retry would send them again", modelType, forwardedChars));
            if (providerFailure != null) {
                addSuppressed(providerFailure);
            }
        }
    }

    private final StreamingChatModel delegate;
    private final ConversationEventSink eventSink;
    private final long timeoutSeconds;
    private final String modelType;

    /**
     * Exactly the text forwarded to the sink by the most recent completed call.
     * Written only by {@link #chat} after its latch is released; the tool loop is
     * single-threaded per conversation turn, so no further synchronization is
     * needed for reads by the caller.
     */
    private volatile String lastForwardedText = "";

    /**
     * Whether ANY earlier round of this turn forwarded text — the trigger for the
     * inter-round separator. Without it, interim commentary and the final answer
     * run together on the live stream ("Let me check…There are 3"). The separator
     * goes to the sink only, never into the per-call forwarded record, so the
     * caller's exact-match suppression still compares pure round text. Written
     * under {@code streamLock}.
     */
    private volatile boolean anyRoundForwarded;

    ToolLoopStreamingChatModel(StreamingChatModel delegate, ConversationEventSink eventSink, long timeoutSeconds, String modelType) {
        this.delegate = delegate;
        this.eventSink = eventSink;
        this.timeoutSeconds = timeoutSeconds;
        this.modelType = modelType;
    }

    /**
     * The text token-forwarded by the last completed model round — {@code ""} when
     * that round forwarded nothing (JSON format, buffered provider, or a pure
     * tool-call round). Callers compare this against the turn's final response text
     * to decide whether the single-chunk fallback emit is still required.
     */
    String lastForwardedText() {
        return lastForwardedText;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        // Partial JSON is not renderable — run over the streaming transport but
        // do not forward tokens for this round.
        boolean forward = chatRequest.responseFormat() == null;

        var latch = new CountDownLatch(1);
        var forwarded = new StringBuilder();
        var errorRef = new AtomicReference<Throwable>();
        var responseRef = new AtomicReference<ChatResponse>();
        // Abandoning (timeout/interrupt) cannot cancel the provider's callback
        // thread; the gate + lock silence it so a late token never writes to the
        // shared sink alongside a retry's stream. See StreamingLegacyChatExecutor
        // for the full reasoning — same pattern, same reasons.
        var abandoned = new AtomicBoolean(false);
        final Object streamLock = new Object();

        delegate.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (!forward) {
                    return;
                }
                synchronized (streamLock) {
                    if (abandoned.get()) {
                        return;
                    }
                    boolean firstForwardOfThisCall = forwarded.isEmpty();
                    forwarded.append(partialResponse);
                    try {
                        if (firstForwardOfThisCall && anyRoundForwarded) {
                            eventSink.onToken("\n\n");
                        }
                        eventSink.onToken(partialResponse);
                    } catch (Exception e) {
                        LOGGER.warnf("Error sending token event: %s", e.getMessage());
                    }
                    anyRoundForwarded = true;
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                responseRef.set(completeResponse);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        boolean completed;
        try {
            completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            synchronized (streamLock) {
                abandoned.set(true);
            }
            Thread.currentThread().interrupt();
            throw new RuntimeException(String.format("[%s] Streaming chat request interrupted", modelType), e);
        }

        if (!completed) {
            synchronized (streamLock) {
                abandoned.set(true);
            }
            // Counted here, when EDDI gives up (L3) — see StreamingLegacyChatExecutor.
            // This class is not CDI-managed, hence the global registry (the idiom
            // ToolContextBudget and LifecycleManager use).
            try {
                Metrics.globalRegistry.counter("eddi.llm.stream.timeouts", "path", "tool_loop").increment();
            } catch (RuntimeException e) {
                LOGGER.debugf("stream-timeout metric emit failed: %s", e.getMessage());
            }
            // Same shape as ObservableChatModel's timeout — including the typed
            // TimeoutException CAUSE, which is what RetryConfiguration
            // .isRetryableError keys on. Without it a provider timeout on the
            // streaming transport would fail the turn immediately while the
            // synchronous path retries with backoff.
            RuntimeException timeout = new RuntimeException(
                    String.format("[%s] Chat request timed out after %dms", modelType, timeoutSeconds * 1000),
                    new TimeoutException("streaming chat exceeded " + timeoutSeconds + "s backstop"));
            throw partiallyStreamed(forwarded, streamLock, timeout);
        }

        Throwable error = errorRef.get();
        if (error != null) {
            synchronized (streamLock) {
                abandoned.set(true);
            }
            throw partiallyStreamed(forwarded, streamLock, error instanceof RuntimeException re ? re : new RuntimeException(error));
        }

        synchronized (streamLock) {
            lastForwardedText = forwarded.toString();
        }
        return responseRef.get();
    }

    /**
     * {@code failure} as it should leave this round: unchanged when nothing was
     * forwarded (the retry may run the round again), otherwise a
     * {@link PartiallyStreamedRoundException} the retry will not repeat.
     */
    private RuntimeException partiallyStreamed(StringBuilder forwarded, Object streamLock, RuntimeException failure) {
        int forwardedChars;
        synchronized (streamLock) {
            forwardedChars = forwarded.length();
        }
        if (forwardedChars == 0) {
            return failure;
        }
        LOGGER.warnf("[%s] Streaming model round failed after %d characters were sent to the client; not retrying it: %s", modelType,
                forwardedChars, failure.getMessage());
        return new PartiallyStreamedRoundException(modelType, forwardedChars, failure);
    }
}
