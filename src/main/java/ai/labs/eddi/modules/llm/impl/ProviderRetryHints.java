/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.configs.shared.LlmFailure;
import ai.labs.eddi.configs.shared.LlmFailureClassifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads what a provider said when it refused a call for its quota: how long to
 * wait, and whether the window that ran out is a minute or a day.
 *
 * <h2>What there is to read</h2>
 *
 * langchain4j (1.23) turns an HTTP 429 into a {@code RateLimitException} whose
 * cause is an {@code HttpException} carrying the status code and the response
 * <b>body as its message</b> — {@code JdkHttpClient.execute} builds it from the
 * raw bytes, and {@code ProviderRetryHintsTest} pins that against a real
 * {@code GoogleAiEmbeddingModel}. The response headers are dropped, so an HTTP
 * {@code Retry-After} header never reaches EDDI through a langchain4j provider
 * client; what the body says is all there is:
 * <ul>
 * <li>Gemini's {@code error.details[]} entry of type
 * {@code google.rpc.RetryInfo}, field {@code retryDelay} ({@code "26s"},
 * {@code "13.047485s"});</li>
 * <li>otherwise the "retry in 13s" / "try again in 20ms" sentence OpenAI and
 * Gemini put in the message;</li>
 * <li>otherwise nothing, and the caller falls back to
 * {@link #FALLBACK_DELAY}.</li>
 * </ul>
 * The parsing itself is {@link LlmFailureClassifier}'s, which the chat path
 * already relies on; this class only adds the minute-or-day judgement the
 * ingestion path needs.
 *
 * <h2>Minute or day</h2>
 *
 * A quota id says it outright — {@code …PerMinute…} or {@code …PerDay…}.
 * Without one, a delay longer than {@link #DAILY_THRESHOLD} is treated as a
 * daily exhaustion: no per-minute window asks for that long.
 *
 * <h2>Not a throttle</h2>
 *
 * An OpenAI {@code insufficient_quota} is a spent balance, which no wait
 * refills: {@link #parse} does not read it as a throttle at all, and
 * {@link #isBillingFailure} tells it apart so the run can fail with that
 * reason.
 *
 * <h2>When a day comes back</h2>
 *
 * Gemini's requests-per-day quotas reset at midnight Pacific time, as its
 * rate-limit documentation states; {@link #dailyReset} computes that instant.
 * For other providers the reset is not known, and the caller uses a configured
 * cooldown.
 */
public final class ProviderRetryHints {

    /** Where Gemini's daily quotas reset: midnight in this zone. */
    static final ZoneId GEMINI_RESET_ZONE = ZoneId.of("America/Los_Angeles");

    /** The wait assumed when a provider throttles without saying for how long. */
    public static final Duration FALLBACK_DELAY = Duration.ofSeconds(30);

    /**
     * A delay longer than this, with no quota id to say otherwise, is a daily
     * exhaustion.
     */
    public static final Duration DAILY_THRESHOLD = Duration.ofMinutes(5);

    private ProviderRetryHints() {
    }

    /**
     * What a provider's refusal says about its quota.
     *
     * @param retryAfter
     *            the delay the provider asked for; null when it gave none
     * @param daily
     *            the window that ran out does not refill within a run: stop rather
     *            than wait
     * @param quotaId
     *            the violated quota's id when the provider named one; null
     *            otherwise. Third-party text — sanitise before logging
     */
    public record Hint(Duration retryAfter, boolean daily, String quotaId) {

        /** The provider's delay, or {@link #FALLBACK_DELAY} when it gave none. */
        public Duration waitOrFallback() {
            return retryAfter != null ? retryAfter : FALLBACK_DELAY;
        }
    }

    /**
     * Reads a failure as a quota refusal.
     *
     * @return the hint, or empty when the failure is not a throttle at all — an
     *         authentication error, a bad request, a provider outage
     */
    public static Optional<Hint> parse(Throwable failure) {
        LlmFailure verdict = LlmFailureClassifier.classify(failure);
        if (verdict.cls() != FailureClass.RATE_LIMITED && verdict.cls() != FailureClass.QUOTA_EXHAUSTED) {
            return Optional.empty();
        }
        if (LlmFailureClassifier.isBillingFailure(failure)) {
            // A spent balance: nothing to wait for.
            return Optional.empty();
        }
        Duration delay = verdict.retryAfterMs() == null || verdict.retryAfterMs() < 0
                ? null
                : Duration.ofMillis(verdict.retryAfterMs());
        String quotaId = LlmFailureClassifier.violatedQuotaId(failure);
        return Optional.of(new Hint(delay, isDaily(verdict.cls(), quotaId, delay), quotaId));
    }

    /**
     * Whether a failure is a spent balance or billing limit, which no wait refills
     * — see {@link LlmFailureClassifier#isBillingFailure}.
     */
    public static boolean isBillingFailure(Throwable failure) {
        return LlmFailureClassifier.isBillingFailure(failure);
    }

    /**
     * When a provider's daily quota next comes back, where it is documented.
     *
     * @return for {@code gemini}, the next midnight in Pacific time after
     *         {@code now}; empty for every other provider, whose reset is not known
     */
    public static Optional<Instant> dailyReset(String provider, Instant now) {
        if (!"gemini".equals(provider)) {
            // Vertex quotas are a Cloud project's, with their own windows: not assumed.
            return Optional.empty();
        }
        LocalDate tomorrow = now.atZone(GEMINI_RESET_ZONE).toLocalDate().plusDays(1);
        return Optional.of(tomorrow.atStartOfDay(GEMINI_RESET_ZONE).toInstant());
    }

    private static boolean isDaily(FailureClass cls, String quotaId, Duration delay) {
        if (cls == FailureClass.QUOTA_EXHAUSTED) {
            // The classifier looked at every violation, not only the first: one per-day
            // quota among several is enough.
            return true;
        }
        String id = quotaId == null ? "" : quotaId.toLowerCase(Locale.ROOT);
        if (id.contains("perday") || id.contains("per_day")) {
            return true;
        }
        if (id.contains("perminute") || id.contains("per_minute")) {
            return false;
        }
        return delay != null && delay.compareTo(DAILY_THRESHOLD) > 0;
    }
}
