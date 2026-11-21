/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.embedding.request.EmbeddingRequestParameters;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import static ai.labs.eddi.modules.llm.impl.ProviderRetryHintsTest.PER_MINUTE_QUOTA;
import static ai.labs.eddi.modules.llm.impl.ProviderRetryHintsTest.fixture;
import static ai.labs.eddi.modules.llm.impl.ProviderRetryHintsTest.rateLimited;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ThrottledEmbeddingModel} over a scripted provider: what it waits for,
 * when it stops instead, and what it leaves alone. Nothing sleeps — the sleeper
 * advances a fake clock.
 */
class ThrottledEmbeddingModelTest {

    private static final List<TextSegment> SEGMENTS = List.of(TextSegment.from("one"), TextSegment.from("two"));

    private final Instant start = Instant.parse("2026-11-21T10:00:00Z");
    private Instant now = start;
    private final List<Duration> sleeps = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final Supplier<Instant> clock = () -> now;
    private final ThrottledEmbeddingModel.Sleeper sleeper = duration -> {
        sleeps.add(duration);
        now = now.plus(duration);
    };

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    /** Answers from a script: each entry throws, or (null) returns embeddings. */
    private static class Scripted implements EmbeddingModel {
        final Deque<RuntimeException> script = new ArrayDeque<>();
        RuntimeException always;
        int calls;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            calls++;
            if (always != null) {
                throw always;
            }
            RuntimeException next = script.poll();
            if (next != null) {
                throw next;
            }
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1f})).toList());
        }
    }

    private ThrottledEmbeddingModel throttled(EmbeddingModel delegate, int maxRetries, boolean retryTransient) {
        return ThrottledEmbeddingModel.of(delegate, "gemini", maxRetries, retryTransient, meters, sleeper, clock);
    }

    @Test
    @DisplayName("the live 429 is slept out for its 26 s and the call then succeeds")
    void waitsThenSucceeds() {
        var provider = new Scripted();
        provider.script.add(rateLimited(fixture("gemini-429-embed-per-minute")));
        EmbeddingModel model = ThrottledEmbeddingModel.boundTo(throttled(provider, 5, true), start.plus(Duration.ofMinutes(10)));

        assertEquals(2, model.embedAll(SEGMENTS).content().size());

        assertEquals(List.of(Duration.ofSeconds(26)), sleeps);
        assertEquals(2, provider.calls);
        assertEquals(1, meters.timer(ThrottledEmbeddingModel.WAIT_TIMER, "provider", "gemini").count());
    }

    @Test
    @DisplayName("a wait that would end after the deadline is not started: the call stops as throttled")
    void waitPastTheDeadlineStops() {
        var provider = new Scripted();
        provider.script.add(rateLimited(fixture("gemini-429-embed-per-minute")));
        EmbeddingModel model = ThrottledEmbeddingModel.boundTo(throttled(provider, 5, true), start.plusSeconds(10));

        var e = assertThrows(ProviderThrottledException.class, () -> model.embedAll(SEGMENTS));

        assertTrue(sleeps.isEmpty(), "nothing is slept that cannot finish in time");
        assertFalse(e.daily());
        assertEquals(Duration.ofSeconds(26), e.retryAfter());
        assertEquals(PER_MINUTE_QUOTA, e.quotaId());
        assertEquals(1, provider.calls);
    }

    @Test
    @DisplayName("a daily quota stops at once, however much time the run has")
    void dailyStopsImmediately() {
        var provider = new Scripted();
        provider.script.add(rateLimited(fixture("gemini-429-embed-per-day")));
        EmbeddingModel model = ThrottledEmbeddingModel.boundTo(throttled(provider, 5, true), start.plus(Duration.ofHours(24)));

        var e = assertThrows(ProviderThrottledException.class, () -> model.embedAll(SEGMENTS));

        assertTrue(e.daily());
        assertTrue(sleeps.isEmpty());
        assertEquals(1, provider.calls);
    }

    @Test
    @DisplayName("a provider that keeps refusing is given maxRetries waits, then the call stops as throttled")
    void retriesExhausted() {
        var provider = new Scripted();
        provider.always = rateLimited(fixture("gemini-429-embed-per-minute"));
        EmbeddingModel model = ThrottledEmbeddingModel.boundTo(throttled(provider, 2, true), start.plus(Duration.ofHours(1)));

        assertThrows(ProviderThrottledException.class, () -> model.embedAll(SEGMENTS));

        assertEquals(2, sleeps.size());
        assertEquals(3, provider.calls);
    }

    @Test
    @DisplayName("without a bound deadline, one call waits at most two minutes in total")
    void unboundCap() {
        var provider = new Scripted();
        provider.always = rateLimited(fixture("gemini-429-embed-per-minute"));
        var model = throttled(provider, 10, true);

        assertThrows(ProviderThrottledException.class, () -> model.embedAll(SEGMENTS));

        // 4 x 26 s = 104 s fits in 120 s; a fifth would end at 130 s.
        assertEquals(4, sleeps.size());
    }

    @Test
    @DisplayName("a transient failure is retried with a short backoff when the provider's own retries are off")
    void transientRetried() {
        var provider = new Scripted();
        provider.script.add(new InternalServerException(new HttpException(503, fixture("gemini-503-unavailable"))));
        provider.script.add(new InternalServerException(new HttpException(503, fixture("gemini-503-unavailable"))));

        assertEquals(2, throttled(provider, 5, true).embedAll(SEGMENTS).content().size());

        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps);
        assertEquals(0, meters.timer(ThrottledEmbeddingModel.WAIT_TIMER, "provider", "gemini").count(),
                "a backoff is not a throttle wait");
    }

    @Test
    @DisplayName("a transient failure is left to the provider's own retries when they are still on")
    void transientNotRetriedWhenTheProviderRetries() {
        var provider = new Scripted();
        var outage = new InternalServerException(new HttpException(503, fixture("gemini-503-unavailable")));
        provider.script.add(outage);

        assertSame(outage, assertThrows(InternalServerException.class,
                () -> throttled(provider, 5, false).embedAll(SEGMENTS)));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    @DisplayName("a failure that is neither throttle nor transient is rethrown untouched")
    void otherFailuresRethrown() {
        var provider = new Scripted();
        var auth = new AuthenticationException(new HttpException(401, fixture("openai-401-invalid-key")));
        provider.script.add(auth);

        assertSame(auth, assertThrows(AuthenticationException.class,
                () -> throttled(provider, 5, true).embedAll(SEGMENTS)));
        assertTrue(sleeps.isEmpty());
        assertEquals(1, provider.calls);
    }

    @Test
    @DisplayName("an interrupted wait stops the call as throttled and keeps the interrupt")
    void interruptedWait() {
        var provider = new Scripted();
        provider.always = rateLimited(fixture("gemini-429-embed-per-minute"));
        var model = ThrottledEmbeddingModel.of(provider, "gemini", 5, true, meters, duration -> {
            throw new InterruptedException();
        }, clock);

        assertThrows(ProviderThrottledException.class, () -> model.embedAll(SEGMENTS));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    @DisplayName("the delegate's request parameters — the DOCUMENT role — are forwarded, not dropped")
    void roleForwarded() {
        var parameters = EmbeddingRequestParameters.builder().inputType(EmbeddingInputType.DOCUMENT).build();
        EmbeddingModel typed = new Scripted() {
            @Override
            public EmbeddingRequestParameters defaultRequestParameters() {
                return parameters;
            }
        };

        assertSame(parameters, throttled(typed, 5, true).defaultRequestParameters());
        assertSame(parameters, ThrottledEmbeddingModel.boundTo(throttled(typed, 5, true), start)
                .defaultRequestParameters());
    }

    @Test
    @DisplayName("binding a deadline leaves a model that does not wait on throttles as it is")
    void boundToLeavesOtherModels() {
        var plain = new Scripted();
        assertSame(plain, ThrottledEmbeddingModel.boundTo(plain, start));
    }
}
