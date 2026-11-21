/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.configs.shared.LlmFailureClassifier;
import ai.labs.eddi.utils.LogSanitizer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.embedding.request.EmbeddingParameter;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.request.EmbeddingRequestParameters;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Waits out a provider's rate limit on the ingestion side, and says so when it
 * cannot.
 *
 * <h2>The defect this fixes</h2>
 *
 * A 68-file Shared Drive ingested with {@code gemini-embedding-2} on the free
 * tier hit
 * {@code EmbedContentRequestsPerMinutePerUserPerProjectPerModel-FreeTier}
 * (limit 100) and was told {@code retryDelay: 26s}. langchain4j's own retry
 * waits half a second, then three quarters, and ignores the delay; it gave up
 * within about a second, the pipeline counted the document failed, and the next
 * document hit the same wall — 49 of 68 failed. A quota belongs to the
 * provider's project, not to the document.
 *
 * <h2>What it does</h2>
 * <ul>
 * <li>A refusal whose window is a minute is slept out — for the delay the
 * provider asked for ({@link ProviderRetryHints}) — and the call repeated, up
 * to {@code maxRetries} times, as long as the wait fits before the caller's
 * deadline.</li>
 * <li>Anything else — a daily exhaustion, a wait past the deadline, a provider
 * that keeps refusing — is thrown as {@link ProviderThrottledException}, which
 * a caller can tell from a failed document.</li>
 * <li>For a provider whose own retries the factory switched off, a transient
 * failure (5xx, timeout) is retried here with a short backoff, so turning off
 * the provider's retries does not cost that.</li>
 * </ul>
 *
 * <h2>Only ever for ingestion</h2>
 *
 * {@link EmbeddingModelFactory} applies this to the {@code DOCUMENT} role
 * alone. A query embedding answers a user who is waiting for a reply, so
 * retrieval keeps langchain4j's short retry and never sleeps on a throttle: a
 * throttled query fails that knowledge base's retrieval, which
 * {@code RagContextProvider} already tolerates.
 *
 * <h2>Deadlines</h2>
 *
 * The cached instance is shared by every run of every source on the same
 * configuration, so it cannot hold a run's deadline. A run binds one with
 * {@link #boundTo}, which returns a light view over the same provider model. An
 * unbound instance — the direct-ingestion REST path — waits at most
 * {@link #UNBOUND_MAX_WAIT} in total per call.
 */
public final class ThrottledEmbeddingModel implements EmbeddingModel {

    private static final Logger LOGGER = Logger.getLogger(ThrottledEmbeddingModel.class);

    /** Retries when the configuration sets no {@code maxRetries}. */
    public static final int DEFAULT_MAX_RETRIES = 5;

    /** The total wait per call allowed to a caller that bound no deadline. */
    static final Duration UNBOUND_MAX_WAIT = Duration.ofMinutes(2);

    /** The first backoff after a transient failure; doubled per attempt. */
    static final Duration TRANSIENT_BACKOFF = Duration.ofSeconds(1);

    /** The longest backoff after a transient failure. */
    static final Duration MAX_TRANSIENT_BACKOFF = Duration.ofSeconds(30);

    static final String WAIT_TIMER = "eddi.provider.throttle.wait";

    /** Waits between attempts; replaced in tests so nothing actually sleeps. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final Sleeper THREAD_SLEEPER = duration -> Thread.sleep(duration.toMillis());

    private final EmbeddingModel delegate;
    private final String provider;
    private final int maxRetries;
    private final boolean retryTransient;
    private final MeterRegistry meterRegistry;
    private final Sleeper sleeper;
    private final Supplier<Instant> clock;
    /** Null on the shared instance; set on a run's view. */
    private final Instant deadline;

    private ThrottledEmbeddingModel(EmbeddingModel delegate, String provider, int maxRetries, boolean retryTransient,
            MeterRegistry meterRegistry, Sleeper sleeper, Supplier<Instant> clock, Instant deadline) {
        this.delegate = delegate;
        this.provider = provider;
        this.maxRetries = Math.max(0, maxRetries);
        this.retryTransient = retryTransient;
        this.meterRegistry = meterRegistry;
        this.sleeper = sleeper;
        this.clock = clock;
        this.deadline = deadline;
    }

    /**
     * @param delegate
     *            the provider's model, already tagged with its role
     * @param provider
     *            the configured provider name, for messages and the metric tag
     * @param maxRetries
     *            how many times one call is repeated, after a throttle or a
     *            transient failure
     * @param retryTransient
     *            whether transient failures are retried here — true exactly when
     *            the provider's own retries were switched off
     * @param meterRegistry
     *            where waits are timed; null records nothing
     */
    static ThrottledEmbeddingModel wrap(EmbeddingModel delegate, String provider, int maxRetries,
                                        boolean retryTransient, MeterRegistry meterRegistry) {
        return of(delegate, provider, maxRetries, retryTransient, meterRegistry, THREAD_SLEEPER, Instant::now);
    }

    /**
     * As {@link #wrap}, with the clock and the sleep supplied — for tests, which
     * must neither sleep nor depend on the wall clock.
     */
    public static ThrottledEmbeddingModel of(EmbeddingModel delegate, String provider, int maxRetries,
                                             boolean retryTransient, MeterRegistry meterRegistry, Sleeper sleeper, Supplier<Instant> clock) {
        return new ThrottledEmbeddingModel(delegate, provider, maxRetries, retryTransient, meterRegistry, sleeper,
                clock, null);
    }

    /**
     * {@code model} bound to a run's deadline when it waits on throttles, or
     * {@code model} itself. A wait that would end after {@code deadline} is not
     * started: the call throws {@link ProviderThrottledException} instead, so the
     * run can stop in time and say why.
     */
    public static EmbeddingModel boundTo(EmbeddingModel model, Instant deadline) {
        if (model instanceof ThrottledEmbeddingModel throttled && deadline != null) {
            return new ThrottledEmbeddingModel(throttled.delegate, throttled.provider, throttled.maxRetries,
                    throttled.retryTransient, throttled.meterRegistry, throttled.sleeper, throttled.clock, deadline);
        }
        return model;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        return call(() -> delegate.embedAll(textSegments));
    }

    @Override
    public Response<Embedding> embed(String text) {
        return call(() -> delegate.embed(text));
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return call(() -> delegate.embed(textSegment));
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        return call(() -> delegate.embed(request));
    }

    @Override
    public EmbeddingResponse doEmbed(EmbeddingRequest request) {
        return call(() -> delegate.doEmbed(request));
    }

    /**
     * Forwarded, or the role the delegate carries would be lost:
     * {@code EmbeddingModel.embed} merges these into every request.
     */
    @Override
    public EmbeddingRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public Set<EmbeddingParameter<?>> supportedParameters() {
        return delegate.supportedParameters();
    }

    @Override
    public Set<ContentType> supportedContentTypes() {
        return delegate.supportedContentTypes();
    }

    @Override
    public List<EmbeddingModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    /** Test-visible: the model underneath. */
    EmbeddingModel delegate() {
        return delegate;
    }

    /** Test-visible: the retries this instance allows. */
    int maxRetries() {
        return maxRetries;
    }

    /** Test-visible: whether transient failures are retried here. */
    boolean retriesTransientFailures() {
        return retryTransient;
    }

    private <T> T call(Supplier<T> action) {
        Instant limit = deadline != null ? deadline : clock.get().plus(UNBOUND_MAX_WAIT);
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (ProviderThrottledException e) {
                throw e;
            } catch (RuntimeException e) {
                Optional<ProviderRetryHints.Hint> throttle = ProviderRetryHints.parse(e);
                Duration wait = throttle.isPresent()
                        ? waitForThrottle(throttle.get(), attempt, limit, e)
                        : waitForTransient(e, attempt, limit);
                sleep(wait, throttle.orElse(null), e);
                attempt++;
            }
        }
    }

    /**
     * How long to wait before repeating a throttled call — or the exception that
     * says this call will not be repeated.
     */
    private Duration waitForThrottle(ProviderRetryHints.Hint hint, int attempt, Instant limit, RuntimeException cause) {
        if (hint.daily()) {
            // A day's quota does not come back within a run, whatever the run's budget.
            throw new ProviderThrottledException(provider, hint.retryAfter(), true, hint.quotaId(), cause);
        }
        Duration wait = hint.waitOrFallback();
        if (attempt >= maxRetries || clock.get().plus(wait).isAfter(limit)) {
            throw new ProviderThrottledException(provider, wait, false, hint.quotaId(), cause);
        }
        return wait;
    }

    /**
     * How long to back off after a transient failure, or the original failure
     * rethrown when it is not one, not retried here, or out of attempts or time.
     */
    private Duration waitForTransient(RuntimeException failure, int attempt, Instant limit) {
        if (!retryTransient || attempt >= maxRetries || !isTransient(failure)) {
            throw failure;
        }
        Duration backoff = TRANSIENT_BACKOFF.multipliedBy(1L << Math.min(attempt, 20));
        if (backoff.compareTo(MAX_TRANSIENT_BACKOFF) > 0) {
            backoff = MAX_TRANSIENT_BACKOFF;
        }
        if (clock.get().plus(backoff).isAfter(limit)) {
            throw failure;
        }
        return backoff;
    }

    private static boolean isTransient(Throwable failure) {
        FailureClass cls = LlmFailureClassifier.classify(failure).cls();
        return cls == FailureClass.TRANSIENT || cls == FailureClass.TIMEOUT;
    }

    private void sleep(Duration wait, ProviderRetryHints.Hint throttle, RuntimeException cause) {
        if (throttle != null) {
            LOGGER.infof("Embedding provider %s is rate-limiting ingestion%s; waiting %d s before trying again",
                    LogSanitizer.sanitize(provider),
                    throttle.quotaId() == null ? "" : " (quota " + LogSanitizer.sanitize(throttle.quotaId()) + ")",
                    wait.toSeconds());
            if (meterRegistry != null) {
                meterRegistry.timer(WAIT_TIMER, "provider", String.valueOf(provider)).record(wait);
            }
        }
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (throttle != null) {
                // Stopping, not failing: the provider's refusal is still the reason.
                throw new ProviderThrottledException(provider, wait, false, throttle.quotaId(), cause);
            }
            throw cause;
        }
    }
}
