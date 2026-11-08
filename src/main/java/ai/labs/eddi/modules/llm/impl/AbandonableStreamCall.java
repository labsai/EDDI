/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One streaming model call, awaited synchronously, that the caller can abandon.
 * <p>
 * The single implementation of the latch / lock / abandon skeleton shared by
 * {@link StreamingLegacyChatExecutor} (the legacy streaming path) and
 * {@link ToolLoopStreamingChatModel} (the tool loop's streaming bridge). Each
 * call gets a fresh instance, passed to the provider as its
 * {@link StreamingChatResponseHandler}.
 * <p>
 * <strong>Why abandoning needs a gate.</strong> Giving up on a call (timeout,
 * interrupt, or an error the caller retries) does not stop the provider's
 * callback thread — nothing here can cancel it. Without a gate a late token
 * from an abandoned call keeps writing to the shared event sink while the retry
 * streams its own tokens into the same sink, so the client renders two answers
 * interleaved and neither matches what is stored in memory. Once a call is
 * abandoned its handler goes silent.
 * <p>
 * <strong>Why the gate needs a lock.</strong> Recording, forwarding and
 * abandoning are mutually exclusive. The forwarded text is written only by the
 * provider's callback thread and read by the caller; on the normal path the
 * latch publishes those writes, but the timeout and interrupt paths have no
 * such edge, and reading the {@link StringBuilder} while it is being appended
 * to can observe a torn buffer (count advanced before the characters are
 * visible, or a stale array after a grow). The same lock closes the
 * check-then-act on the gate: a callback already past the check could otherwise
 * still push a token into the sink after the caller believed the call silenced.
 * Under the lock an in-flight emission completes first and is therefore
 * included in {@link #forwardedText()} — never half-included.
 */
final class AbandonableStreamCall implements StreamingChatResponseHandler {

    /**
     * Receives each partial token of a live call. Invoked under the call's lock,
     * never after the call has been abandoned.
     */
    @FunctionalInterface
    interface TokenForwarder {
        /**
         * @param token
         *            the partial text, already recorded in {@link #forwardedText()}
         * @param firstOfCall
         *            {@code true} when nothing had been recorded for this call before
         *            this token
         */
        void forward(String token, boolean firstOfCall);
    }

    /** {@code null}: partials are neither recorded nor forwarded. */
    private final TokenForwarder forwarder;
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<ChatResponse> response = new AtomicReference<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final StringBuilder forwarded = new StringBuilder();
    /** Guarded by {@link #lock}. */
    private boolean abandoned;

    private AbandonableStreamCall(TokenForwarder forwarder) {
        this.forwarder = forwarder;
    }

    /** A call that records each partial token and hands it to {@code forwarder}. */
    static AbandonableStreamCall forwardingTo(TokenForwarder forwarder) {
        return new AbandonableStreamCall(forwarder);
    }

    /**
     * A call whose partial tokens are dropped — for a round whose partial output is
     * not renderable (an API-level JSON response format). It still completes, fails
     * and times out like any other call.
     */
    static AbandonableStreamCall discardingPartials() {
        return new AbandonableStreamCall(null);
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        if (forwarder == null) {
            return;
        }
        synchronized (lock) {
            if (abandoned) {
                return;
            }
            boolean firstOfCall = forwarded.isEmpty();
            forwarded.append(partialResponse);
            forwarder.forward(partialResponse, firstOfCall);
        }
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        response.set(completeResponse);
        done.countDown();
    }

    @Override
    public void onError(Throwable throwable) {
        error.set(throwable);
        done.countDown();
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
        try {
            if (done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            abandon();
            throw e;
        }
        abandon();
        return false;
    }

    /** Silence the call: no partial token is recorded or forwarded after this. */
    void abandon() {
        synchronized (lock) {
            abandoned = true;
        }
    }

    /**
     * Run {@code action} under the call's lock unless the call has been abandoned —
     * for an emission to the same sink outside the token stream, which must obey
     * the same gate.
     */
    void runUnlessAbandoned(Runnable action) {
        synchronized (lock) {
            if (!abandoned) {
                action.run();
            }
        }
    }

    /**
     * Exactly the text forwarded so far — for an abandoned call, all it ever
     * forwarded.
     */
    String forwardedText() {
        synchronized (lock) {
            return forwarded.toString();
        }
    }

    /** The complete response, or {@code null} when the call has not completed. */
    ChatResponse response() {
        return response.get();
    }

    /** The provider's failure, or {@code null} when it reported none. */
    Throwable error() {
        return error.get();
    }
}
