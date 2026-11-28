/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
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
