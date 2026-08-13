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
import org.jboss.logging.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * <strong>Known limitation (documented, accepted):</strong> the tool loop
 * retries whole attempts. If an attempt fails after some tokens were already
 * forwarded, the retry streams again from the start of the attempt, so the
 * client may see a repeated prefix during a provider flake. Memory is
 * unaffected — it stores only the final returned text.
 */
class ToolLoopStreamingChatModel implements ChatModel {
    private static final Logger LOGGER = Logger.getLogger(ToolLoopStreamingChatModel.class);

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
            // Same shape as ObservableChatModel's timeout so the loop's retry and
            // failure handling see an identical signal.
            throw new RuntimeException(String.format("[%s] Chat request timed out after %dms", modelType, timeoutSeconds * 1000));
        }

        Throwable error = errorRef.get();
        if (error != null) {
            synchronized (streamLock) {
                abandoned.set(true);
            }
            throw error instanceof RuntimeException re ? re : new RuntimeException(error);
        }

        synchronized (streamLock) {
            lastForwardedText = forwarded.toString();
        }
        return responseRef.get();
    }
}
