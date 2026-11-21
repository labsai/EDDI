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
import java.util.concurrent.CancellationException;
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
    @DisplayName("without a bound deadline, a call waits at most 10 s by default: a 26 s wait is not started")
    void unboundCapDefault() {
        var provider = new Scripted();
        provider.always = rateLimited(fixture("gemini-429-embed-per-minute"));
        var model = throttled(provider, 10, true);

        var e = assertThrows(ProviderThrottledException.class, () -> model.embedAll(SEGMENTS));

        assertTrue(sleeps.isEmpty(), "a request thread is not put to sleep for 26 s");
        assertEquals(1, provider.calls);
        assertEquals(Duration.ofSeconds(26), e.retryAfter());
    }

    @Test
    @DisplayName("a configured unbound wait is a total across retries")
    void unboundCapConfigured() {
        var provider = new Scripted();
        provider.always = rateLimited(fixture("gemini-429-embed-per-minute"));
        var model = ThrottledEmbeddingModel.of(provider, "gemini", 10, true, meters, sleeper, clock,
                Duration.ofMinutes(2), ThrottledEmbeddingModel.DEFAULT_BATCH_SIZE);

        assertThrows(ProviderThrottledException.class, () -> model.embedAll(SEGMENTS));

        // 4 x 26 s = 104 s fits in 120 s; a fifth would end at 130 s.
        assertEquals(4, sleeps.size());
    }

    @Test
    @DisplayName("a configured unbound wait is a total for the whole call, not per batch")
    void unboundCapSpansBatches() {
        // Every batch is refused once, then answered. Three batches of one segment.
        var provider = new Scripted() {
            boolean refuseNext = true;

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                calls++;
                if (refuseNext) {
                    refuseNext = false;
                    throw rateLimited(fixture("gemini-429-embed-per-minute"));
                }
                refuseNext = true;
                return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1f})).toList());
            }
        };
        var model = ThrottledEmbeddingModel.of(provider, "gemini", 5, true, meters, sleeper, clock,
                Duration.ofSeconds(60), 1);
        var three = List.of(TextSegment.from("a"), TextSegment.from("b"), TextSegment.from("c"));

        assertThrows(ProviderThrottledException.class, () -> model.embedAll(three));

        // 26 s + 26 s = 52 s fits in 60 s; the third batch's wait would end at 78 s.
        assertEquals(2, sleeps.size());
    }

    @Test
    @DisplayName("embedAll is split into batches, each sent once when nothing refuses, in order")
    void batches() {
        var sizes = new ArrayList<Integer>();
        var provider = new Scripted() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                sizes.add(segments.size());
                return Response.from(segments.stream()
                        .map(s -> Embedding.from(new float[]{Float.parseFloat(s.text())})).toList());
            }
        };
        var model = ThrottledEmbeddingModel.of(provider, "gemini", 5, true, meters, sleeper, clock,
                ThrottledEmbeddingModel.DEFAULT_UNBOUND_MAX_WAIT, 2);
        var five = List.of(TextSegment.from("1"), TextSegment.from("2"), TextSegment.from("3"),
                TextSegment.from("4"), TextSegment.from("5"));

        var embeddings = model.embedAll(five).content();

        assertEquals(List.of(2, 2, 1), sizes);
        assertEquals(List.of(1f, 2f, 3f, 4f, 5f), embeddings.stream().map(e -> e.vector()[0]).toList());
    }

    @Test
    @DisplayName("a refused batch is repeated alone: the batches before it are not paid for again")
    void retryRepeatsOnlyTheRefusedBatch() {
        var sizes = new ArrayList<Integer>();
        var provider = new Scripted() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                sizes.add(segments.size());
                if (sizes.size() == 2) {
                    throw rateLimited(fixture("gemini-429-embed-per-minute"));
                }
                return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1f})).toList());
            }
        };
        var model = ThrottledEmbeddingModel.boundTo(ThrottledEmbeddingModel.of(provider, "gemini", 5, true, meters,
                sleeper, clock, ThrottledEmbeddingModel.DEFAULT_UNBOUND_MAX_WAIT, 2), start.plus(Duration.ofHours(1)));
        var four = List.of(TextSegment.from("a"), TextSegment.from("b"), TextSegment.from("c"), TextSegment.from("d"));

        assertEquals(4, model.embedAll(four).content().size());

        assertEquals(List.of(2, 2, 2), sizes, "the first batch once, the second twice");
        assertEquals(List.of(Duration.ofSeconds(26)), sleeps);
    }

    @Test
    @DisplayName("a provider delay under a second is waited as one second, not as a busy loop")
    void minimumWait() {
        var provider = new Scripted();
        provider.script.add(rateLimited(fixture("openai-429-rate-limit-ms")));

        throttled(provider, 5, true).embedAll(SEGMENTS);

        assertEquals(List.of(ThrottledEmbeddingModel.MIN_WAIT), sleeps);
    }

    @Test
    @DisplayName("a stop with no delay named carries none: the 30 s fallback is not reported as the provider's")
    void fallbackIsNotReported() {
        var provider = new Scripted();
        provider.always = rateLimited("<html>Too Many Requests</html>");
        EmbeddingModel model = ThrottledEmbeddingModel.boundTo(throttled(provider, 5, true), start.plusSeconds(10));

        var e = assertThrows(ProviderThrottledException.class, () -> model.embedAll(SEGMENTS));

        assertTrue(sleeps.isEmpty(), "30 s does not fit in 10 s");
        assertEquals(null, e.retryAfter());
    }

    @Test
    @DisplayName("OpenAI's insufficient_quota is a billing failure: no wait, no retry, its own exception")
    void billingFailure() {
        var provider = new Scripted();
        provider.always = rateLimited(fixture("openai-429-insufficient-quota"));
        var model = ThrottledEmbeddingModel.boundTo(throttled(provider, 5, true), start.plus(Duration.ofHours(1)));

        var e = assertThrows(ProviderBillingException.class, () -> model.embedAll(SEGMENTS));

        assertTrue(sleeps.isEmpty());
        assertEquals(1, provider.calls);
        assertTrue(e.getMessage().contains("billing"));
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
    @DisplayName("an interrupted throttle wait is a cancellation, not a provider stop, and keeps the interrupt")
    void interruptedWait() {
        var provider = new Scripted();
        provider.always = rateLimited(fixture("gemini-429-embed-per-minute"));
        var model = ThrottledEmbeddingModel.boundTo(ThrottledEmbeddingModel.of(provider, "gemini", 5, true, meters,
                duration -> {
                    throw new InterruptedException();
                }, clock), start.plus(Duration.ofHours(1)));

        assertThrows(CancellationException.class, () -> model.embedAll(SEGMENTS));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    @DisplayName("an interrupted transient backoff is a cancellation too")
    void interruptedBackoff() {
        var provider = new Scripted();
        provider.always = new InternalServerException(new HttpException(503, fixture("gemini-503-unavailable")));
        var model = ThrottledEmbeddingModel.of(provider, "gemini", 5, true, meters, duration -> {
            throw new InterruptedException();
        }, clock);

        assertThrows(CancellationException.class, () -> model.embedAll(SEGMENTS));
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
