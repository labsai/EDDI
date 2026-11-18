/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.jboss.logging.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * One streaming model call, awaited synchronously, that the caller can abandon.
 * <p>
 * The single implementation of the latch / lock / abandon skeleton shared by
 * every streaming path — the no-tools path and the cascade's live final step
 * ({@link StreamingLegacyChatExecutor}) and the tool loop
 * ({@link ToolLoopStreamingChatModel}). Each call gets a fresh instance, passed
 * to the provider as its {@link StreamingChatResponseHandler}.
 * <p>
 * <strong>The first terminal callback wins.</strong> {@code onCompleteResponse}
 * and {@code onError} each end the call, whichever comes first; the other is
 * ignored, and no partial is forwarded after either. A provider that reported
 * an error and kept streaming used to have its later tokens forwarded to the
 * client but not stored, so the client and the conversation disagreed on the
 * answer.
 * <p>
 * <strong>Why abandoning needs a gate.</strong> Giving up on a call (timeout,
 * idle gap, interrupt, cancel, or an error the caller retries) silences its
 * handler, and — when the provider handed out a {@link StreamingHandle} — asks
 * the provider to stop the stream. A provider that offers no handle keeps
 * calling back; without the gate a late token from an abandoned call would
 * reach the shared event sink next to a retry's tokens.
 * <p>
 * <strong>Why the gate needs a lock.</strong> Recording, forwarding and
 * abandoning are mutually exclusive. The forwarded text is written only by the
 * provider's callback thread and read by the caller; reading the
 * {@link StringBuilder} while it is being appended to can observe a torn
 * buffer. The same lock closes the check-then-act on the gate: under it an
 * in-flight emission completes first and is therefore included in
 * {@link #forwardedText()} — never half-included.
 */
final class AbandonableStreamCall implements StreamingChatResponseHandler {

    private static final Logger LOGGER = Logger.getLogger(AbandonableStreamCall.class);

    /** How a call ended, as {@link #await} saw it. */
    enum Outcome {
        /** {@code onCompleteResponse} came first. */
        COMPLETED,
        /** {@code onError} came first. */
        FAILED,
        /** The wall-clock backstop elapsed. */
        TIMED_OUT,
        /** No stream event for longer than the idle limit, after the first one. */
        IDLE_TIMEOUT,
        /** The turn was cancelled while the call ran. */
        CANCELLED
    }

    /**
     * Receives each partial token of a live call. Invoked under the call's lock,
     * never after the call has ended or been abandoned.
     */
    @FunctionalInterface
    interface TokenForwarder {
        /**
         * @param token
         *            the partial text, already recorded in
         *            {@link AbandonableStreamCall#forwardedText()}
         * @param firstOfCall
         *            {@code true} when nothing had been recorded for this call before
         *            this token
         */
        void forward(String token, boolean firstOfCall);
    }

    /** {@code null}: partials are neither recorded nor forwarded. */
    private final TokenForwarder forwarder;
    private final CountDownLatch done = new CountDownLatch(1);
    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final StringBuilder forwarded = new StringBuilder();
    /** Guarded by {@link #lock}: no partial is recorded or forwarded once set. */
    private boolean abandoned;
    /** Guarded by {@link #lock}: a terminal callback was accepted. */
    private boolean terminated;
    /** Guarded by {@link #lock}. */
    private ChatResponse response;
    /** Guarded by {@link #lock}. */
    private Throwable error;

    /** The provider's handle to stop the stream, once it handed one out. */
    private volatile StreamingHandle streamingHandle;
    /** {@link System#nanoTime()} of the last stream event of any kind. */
    private volatile long lastEventNanos;
    /** Whether any stream event arrived; the idle limit starts with the first. */
    private volatile boolean anyEvent;

    private AbandonableStreamCall(TokenForwarder forwarder) {
        this.forwarder = forwarder;
    }

    /** A call that records each partial token and hands it to {@code forwarder}. */
    static AbandonableStreamCall forwardingTo(TokenForwarder forwarder) {
        return new AbandonableStreamCall(forwarder);
    }

    /**
     * A call whose partial tokens are dropped — for a round whose partial output is
     * not renderable (an API-level JSON response format). It still completes,
     * fails, times out and goes idle like any other call.
     */
    static AbandonableStreamCall discardingPartials() {
        return new AbandonableStreamCall(null);
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        record(partialResponse);
    }

    @Override
    public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
        keepHandle(context != null ? context.streamingHandle() : null);
        record(partialResponse != null ? partialResponse.text() : null);
    }

    @Override
    public void onPartialThinking(PartialThinking partialThinking) {
        touch();
    }

    @Override
    public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
        keepHandle(context != null ? context.streamingHandle() : null);
        touch();
    }

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall) {
        touch();
    }

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
        keepHandle(context != null ? context.streamingHandle() : null);
        touch();
    }

    @Override
    public void onCompleteToolCall(CompleteToolCall completeToolCall) {
        touch();
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        end(completeResponse, null);
    }

    @Override
    public void onError(Throwable throwable) {
        end(null, throwable != null ? throwable : new IllegalStateException("The streaming provider reported an error without detail"));
    }

    /**
     * A partial text token. A {@code null} or empty token is activity, not text: it
     * is neither recorded nor forwarded (a {@code null} used to be appended as the
     * four characters "null").
     */
    private void record(String text) {
        touch();
        if (forwarder == null || text == null || text.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (abandoned) {
                return;
            }
            boolean firstOfCall = forwarded.isEmpty();
            forwarded.append(text);
            forwarder.forward(text, firstOfCall);
        }
    }

    private void end(ChatResponse completeResponse, Throwable failure) {
        synchronized (lock) {
            if (terminated) {
                return;
            }
            terminated = true;
            abandoned = true;
            response = completeResponse;
            error = failure;
        }
        done.countDown();
    }

    private void touch() {
        lastEventNanos = System.nanoTime();
        anyEvent = true;
    }

    private void keepHandle(StreamingHandle handle) {
        if (handle != null && streamingHandle == null) {
            streamingHandle = handle;
        }
    }

    /**
     * Wait for the provider to complete or fail the call. A call that does neither
     * within {@code timeoutSeconds} is abandoned before this returns.
     *
     * @return {@code true} when the call completed or failed, {@code false} when it
     *         timed out (and is now abandoned)
     * @throws InterruptedException
     *             when the waiting thread is interrupted; the call is abandoned
     *             first. The interrupt flag is left cleared, as by any blocking
     *             call — the caller decides how to restore and report it.
     */
    boolean await(long timeoutSeconds) throws InterruptedException {
        Outcome outcome = await(timeoutSeconds, 0L, null);
        return outcome == Outcome.COMPLETED || outcome == Outcome.FAILED;
    }

    /**
     * Wait for the call under {@code policy}: its backstop, its idle limit and its
     * cancel flag. Every outcome but {@link Outcome#COMPLETED} and
     * {@link Outcome#FAILED} abandons the call and stops the provider's stream.
     */
    Outcome await(StreamPolicy policy) throws InterruptedException {
        return await(policy.backstopSeconds(), policy.idleTimeoutSeconds(), policy.cancelled());
    }

    /**
     * @param backstopSeconds
     *            the whole call's bound
     * @param idleSeconds
     *            the longest silence after the first stream event; {@code 0} = none
     * @param cancelled
     *            polled every {@value StreamPolicy#POLL_MILLIS} ms; {@code null} =
     *            never
     * @throws InterruptedException
     *             as {@link #await(long)}
     */
    Outcome await(long backstopSeconds, long idleSeconds, BooleanSupplier cancelled) throws InterruptedException {
        long start = System.nanoTime();
        long backstopNanos = TimeUnit.SECONDS.toNanos(Math.max(0L, backstopSeconds));
        long idleNanos = TimeUnit.SECONDS.toNanos(Math.max(0L, idleSeconds));
        long pollNanos = TimeUnit.MILLISECONDS.toNanos(StreamPolicy.POLL_MILLIS);
        try {
            while (true) {
                if (done.getCount() == 0) {
                    return terminalOutcome();
                }
                long now = System.nanoTime();
                long remaining = backstopNanos - (now - start);
                if (remaining <= 0) {
                    abandonAndCancel();
                    return Outcome.TIMED_OUT;
                }
                long wait = Math.min(remaining, pollNanos);
                if (idleNanos > 0 && anyEvent) {
                    long idleLeft = idleNanos - (now - lastEventNanos);
                    if (idleLeft <= 0) {
                        abandonAndCancel();
                        return Outcome.IDLE_TIMEOUT;
                    }
                    wait = Math.min(wait, idleLeft);
                }
                if (done.await(wait, TimeUnit.NANOSECONDS)) {
                    return terminalOutcome();
                }
                if (cancelled != null && cancelled.getAsBoolean()) {
                    abandonAndCancel();
                    return Outcome.CANCELLED;
                }
            }
        } catch (InterruptedException e) {
            abandonAndCancel();
            throw e;
        }
    }

    private Outcome terminalOutcome() {
        synchronized (lock) {
            return error != null ? Outcome.FAILED : Outcome.COMPLETED;
        }
    }

    /** Silence the call: no partial token is recorded or forwarded after this. */
    void abandon() {
        synchronized (lock) {
            abandoned = true;
        }
    }

    /**
     * Silence the call and ask the provider to stop streaming it, so a timed-out,
     * idle, interrupted or cancelled call stops costing tokens. The handle is
     * called outside the lock: a provider may complete the call from inside
     * {@code cancel()}.
     */
    void abandonAndCancel() {
        abandon();
        StreamingHandle handle = streamingHandle;
        if (handle != null) {
            try {
                if (!handle.isCancelled()) {
                    handle.cancel();
                }
            } catch (RuntimeException e) {
                LOGGER.debugf("Cancelling the provider stream failed: %s", e.getMessage());
            }
        }
    }

    /** Whether the provider handed out a handle the call can be stopped with. */
    boolean cancellable() {
        return streamingHandle != null;
    }

    /**
     * Run {@code action} under the call's lock unless the call has been abandoned
     * or has ended — for an emission to the same sink outside the token stream,
     * which must obey the same gate.
     */
    void runUnlessAbandoned(Runnable action) {
        synchronized (lock) {
            if (!abandoned) {
                action.run();
            }
        }
    }

    /**
     * Exactly the text forwarded so far — for an abandoned or ended call, all it
     * ever forwarded.
     */
    String forwardedText() {
        synchronized (lock) {
            return forwarded.toString();
        }
    }

    /** The complete response, or {@code null} when the call did not complete. */
    ChatResponse response() {
        synchronized (lock) {
            return response;
        }
    }

    /** The provider's failure, or {@code null} when it reported none first. */
    Throwable error() {
        synchronized (lock) {
            return error;
        }
    }
}
