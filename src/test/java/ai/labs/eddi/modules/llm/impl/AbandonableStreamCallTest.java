/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The latch / lock / abandon skeleton both streaming paths share
 * ({@link StreamingLegacyChatExecutor} and {@link ToolLoopStreamingChatModel}).
 * <p>
 * The property everything here protects: once a call is abandoned, the
 * provider's callback thread — which nothing can cancel — must go silent, so a
 * late token never reaches the shared event sink next to a retry's tokens, and
 * the text the caller reads is exactly the text the sink was sent.
 */
@DisplayName("AbandonableStreamCall")
class AbandonableStreamCallTest {

    private static final ChatResponse COMPLETE = ChatResponse.builder().aiMessage(AiMessage.from("done")).build();

    /** What the forwarder received, in order, with its firstOfCall flag. */
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<Boolean> firstFlags = new CopyOnWriteArrayList<>();

    private AbandonableStreamCall recordingCall() {
        return AbandonableStreamCall.forwardingTo((token, firstOfCall) -> {
            sent.add(token);
            firstFlags.add(firstOfCall);
        });
    }

    @Test
    @DisplayName("forwards partials in order, records exactly what it forwarded, and completes")
    void forwardsInOrderAndCompletes() throws InterruptedException {
        var call = recordingCall();

        call.onPartialResponse("Hello ");
        call.onPartialResponse("world");
        call.onCompleteResponse(COMPLETE);

        assertTrue(call.await(30));
        assertEquals(List.of("Hello ", "world"), sent);
        assertEquals(List.of(true, false), firstFlags, "only the first forwarded token of a call is flagged first");
        assertEquals("Hello world", call.forwardedText());
        assertSame(COMPLETE, call.response());
        assertNull(call.error());
    }

    @Test
    @DisplayName("an error releases the await and is reported, with what was forwarded before it")
    void errorReleasesAwait() throws InterruptedException {
        var call = recordingCall();
        var failure = new IllegalStateException("provider 500");

        call.onPartialResponse("partial");
        call.onError(failure);

        assertTrue(call.await(30));
        assertSame(failure, call.error());
        assertNull(call.response());
        assertEquals("partial", call.forwardedText());
    }

    @Test
    @DisplayName("a late token after a timeout is neither recorded nor forwarded")
    void lateTokenAfterTimeoutIsSilenced() throws InterruptedException {
        var call = recordingCall();
        call.onPartialResponse("early ");

        // 0s: the await elapses at once, no test latency.
        assertFalse(call.await(0), "an unanswered call times out");

        call.onPartialResponse("late token");
        call.onCompleteResponse(COMPLETE);

        assertEquals(List.of("early "), sent, "the abandoned call's callback thread must go silent");
        assertEquals("early ", call.forwardedText(), "the recorded text is exactly what the sink was sent");
    }

    @Test
    @DisplayName("an interrupted await abandons the call and rethrows, so the caller decides what an interrupt means")
    void interruptedAwaitAbandons() {
        var call = recordingCall();

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> call.await(30));
        } finally {
            // Clear so the flag never leaks into the next test on this worker.
            Thread.interrupted();
        }

        call.onPartialResponse("late token");
        assertTrue(sent.isEmpty(), "an interrupted call must be silenced like a timed-out one");
        assertEquals("", call.forwardedText());
    }

    @Test
    @DisplayName("an explicit abandon (a retried error) silences the call and stops runUnlessAbandoned")
    void explicitAbandonSilences() {
        var call = recordingCall();
        var ran = new AtomicInteger();

        call.runUnlessAbandoned(ran::incrementAndGet);
        call.abandon();
        call.onPartialResponse("late token");
        call.runUnlessAbandoned(ran::incrementAndGet);

        assertTrue(sent.isEmpty());
        assertEquals(1, ran.get(), "the action runs while the call is live, never after it is abandoned");
    }

    @Test
    @DisplayName("a call that discards partials records and forwards nothing, but still completes")
    void discardingPartials() throws InterruptedException {
        var call = AbandonableStreamCall.discardingPartials();

        call.onPartialResponse("{\"a\":");
        call.onPartialResponse("1}");
        call.onCompleteResponse(COMPLETE);

        assertTrue(call.await(30));
        assertEquals("", call.forwardedText());
        assertSame(COMPLETE, call.response());
    }

    @Test
    @DisplayName("the first terminal callback wins: an error after completion does not turn the answer into a failure")
    void completionThenErrorKeepsTheCompletion() throws InterruptedException {
        var call = recordingCall();

        call.onPartialResponse("done");
        call.onCompleteResponse(COMPLETE);
        call.onError(new IllegalStateException("late error"));

        assertEquals(AbandonableStreamCall.Outcome.COMPLETED, call.await(30, 0, null));
        assertSame(COMPLETE, call.response());
        assertNull(call.error());
    }

    @Test
    @DisplayName("the first terminal callback wins: nothing is forwarded after an error, and a late completion is ignored")
    void errorThenTokensAreNotForwarded() throws InterruptedException {
        var call = recordingCall();
        var failure = new IllegalStateException("malformed frame");

        call.onPartialResponse("good1 ");
        call.onError(failure);
        // The provider keeps streaming after its own error (the live #garbage case):
        // the client must not get text the conversation will never store.
        call.onPartialResponse("good2");
        call.onCompleteResponse(COMPLETE);

        assertEquals(AbandonableStreamCall.Outcome.FAILED, call.await(30, 0, null));
        assertEquals(List.of("good1 "), sent);
        assertEquals("good1 ", call.forwardedText());
        assertSame(failure, call.error());
        assertNull(call.response());
    }

    @Test
    @DisplayName("a null or empty partial is activity, never the text \"null\"")
    void nullPartialIsNotText() throws InterruptedException {
        var call = recordingCall();

        call.onPartialResponse((String) null);
        call.onPartialResponse("");
        call.onPartialResponse("x");
        call.onCompleteResponse(COMPLETE);

        assertTrue(call.await(30));
        assertEquals(List.of("x"), sent);
        assertEquals("x", call.forwardedText());
    }

    @Test
    @DisplayName("a call that went silent after its first token ends IDLE_TIMEOUT and stops the provider's stream")
    void idleGapEndsTheCallAndCancelsTheStream() throws InterruptedException {
        var call = recordingCall();
        var handle = new RecordingHandle();

        call.onPartialResponse(new PartialResponse("first "), new PartialResponseContext(handle));

        // 1s idle limit, 30s backstop: the call ends on the gap, not on the backstop.
        long start = System.nanoTime();
        assertEquals(AbandonableStreamCall.Outcome.IDLE_TIMEOUT, call.await(30, 1, null));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10, "the idle limit, not the backstop, ended the wait");
        assertTrue(handle.cancelled.get(), "an idle stream is cancelled at the provider, not just ignored");

        call.onPartialResponse("late");
        assertEquals(List.of("first "), sent);
    }

    @Test
    @DisplayName("the idle limit does not start before the first event: time to first token is the backstop's business")
    void idleLimitStartsWithTheFirstEvent() throws InterruptedException {
        var call = recordingCall();

        // No event at all for longer than the idle limit, then the answer.
        var provider = new Thread(() -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                return;
            }
            call.onPartialResponse("late but fine");
            call.onCompleteResponse(COMPLETE);
        });
        provider.setDaemon(true);
        provider.start();

        assertEquals(AbandonableStreamCall.Outcome.COMPLETED, call.await(30, 1, null));
        assertEquals("late but fine", call.forwardedText());
    }

    @Test
    @DisplayName("a cancel flag raised while waiting ends the call CANCELLED and cancels the provider's stream")
    void cancelFlagIsPolledAndCancelsTheStream() throws InterruptedException {
        var call = recordingCall();
        var handle = new RecordingHandle();
        var cancelled = new AtomicBoolean();

        call.onPartialResponse(new PartialResponse("token "), new PartialResponseContext(handle));
        cancelled.set(true);

        assertEquals(AbandonableStreamCall.Outcome.CANCELLED, call.await(30, 0, cancelled::get));
        assertTrue(handle.cancelled.get());

        call.onPartialResponse("after cancel");
        assertEquals(List.of("token "), sent, "no token reaches the client after the cancel");
    }

    @Test
    @DisplayName("a timed-out call cancels the provider's stream when the provider handed out a handle")
    void timeoutCancelsTheStream() throws InterruptedException {
        var call = recordingCall();
        var handle = new RecordingHandle();
        call.onPartialResponse(new PartialResponse("a"), new PartialResponseContext(handle));

        assertEquals(AbandonableStreamCall.Outcome.TIMED_OUT, call.await(0, 0, null));
        assertTrue(handle.cancelled.get());
    }

    @Test
    @DisplayName("M2: a call abandoned before its first partial cancels the handle that arrives later — exactly once")
    void lateHandleIsCancelledOnce() throws InterruptedException {
        var call = recordingCall();
        var handle = new RecordingHandle();

        // Timed out before the provider said anything: no handle yet.
        assertEquals(AbandonableStreamCall.Outcome.TIMED_OUT, call.await(0, 0, null));
        assertFalse(call.cancellable());

        // The first partial brings the handle: the provider is stopped at once.
        call.onPartialResponse(new PartialResponse("late"), new PartialResponseContext(handle));
        call.onPartialResponse(new PartialResponse("later"), new PartialResponseContext(handle));

        assertEquals(1, handle.cancels.get(), "cancelled exactly once");
        assertTrue(sent.isEmpty(), "and nothing of it is forwarded");
    }

    @Test
    @DisplayName("S3: a cancel signal wakes a waiting call at once, without waiting for the poll")
    void cancelSignalWakesTheWait() throws InterruptedException {
        var call = recordingCall();
        var handle = new RecordingHandle();
        call.onPartialResponse(new PartialResponse("token "), new PartialResponseContext(handle));
        List<Runnable> listeners = new CopyOnWriteArrayList<>();
        var policy = new StreamPolicy(300, 0, true, () -> false, null, false, listener -> {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }, "test");

        var canceller = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            listeners.forEach(Runnable::run);
        });
        canceller.setDaemon(true);
        long start = System.nanoTime();
        canceller.start();

        assertEquals(AbandonableStreamCall.Outcome.CANCELLED, call.await(policy));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < StreamPolicy.POLL_MILLIS,
                "woken by the signal, not by the next poll");
        assertTrue(handle.cancelled.get());
        assertTrue(listeners.isEmpty(), "the subscription is released when the wait ends");
    }

    @Test
    @DisplayName("a turn already cancelled when the call starts does not wait at all")
    void alreadyCancelledDoesNotWait() throws InterruptedException {
        var call = recordingCall();
        long start = System.nanoTime();

        assertEquals(AbandonableStreamCall.Outcome.CANCELLED, call.await(300, 0, () -> true));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < StreamPolicy.POLL_MILLIS);
    }

    /** A provider stream handle that records the cancel. */
    private static final class RecordingHandle implements StreamingHandle {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public void cancel() {
            cancels.incrementAndGet();
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    /**
     * Two threads: a provider thread streaming fixed two-character tokens as fast
     * as it can, and the caller abandoning mid-stream. Whatever the interleaving,
     * the text read after abandoning must be whole tokens and must equal, token for
     * token, what was forwarded — never a torn buffer and never a token forwarded
     * but missing from the text (or the reverse).
     */
    @Test
    @DisplayName("concurrent appends and an abandon never expose a torn buffer or a forwarded-but-unrecorded token")
    void concurrentAppendAndAbandon() throws InterruptedException {
        for (int round = 0; round < 50; round++) {
            sent.clear();
            firstFlags.clear();
            var call = recordingCall();
            var started = new CountDownLatch(1);
            var stop = new AtomicBoolean(false);

            var provider = new Thread(() -> {
                started.countDown();
                while (!stop.get()) {
                    call.onPartialResponse("ab");
                }
            });
            provider.setDaemon(true);
            provider.start();
            assertTrue(started.await(10, TimeUnit.SECONDS));

            call.abandon();
            String text = call.forwardedText();
            int forwardedAtAbandon = sent.size();
            stop.set(true);
            provider.join(TimeUnit.SECONDS.toMillis(10));

            assertEquals(0, text.length() % 2, "torn buffer: " + text.length() + " chars");
            assertEquals(String.join("", sent), text, "the recorded text must be exactly what the sink was sent");
            assertEquals(forwardedAtAbandon, sent.size(), "nothing may be forwarded after the abandon returns");
        }
    }
}
