/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Runtime configuration for the Slack channel ({@code eddi.slack.*}).
 * <p>
 * These were compile-time constants on {@link SlackEventHandler}, while the
 * OpenAI-compatible adapter treated the identical concerns as configuration.
 * The asymmetry mattered most for the turn timeout: an agent whose turn
 * legitimately takes longer than a minute — a multi-step tool-calling turn, a
 * slow provider, a cascade escalation — always failed on Slack and worked over
 * {@code /v1}, where the operator can raise the limit. There was no way to tune
 * it without a rebuild.
 * <p>
 * The defaults are exactly the constants they replace, so an operator who sets
 * nothing sees no change.
 *
 * @since 6.3.0
 */
@ApplicationScoped
public class SlackConfig {

    /** Default seconds to wait for a single agent turn. */
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;

    /** Default seconds to wait for a whole group discussion. */
    public static final int DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS = 300;

    /** Default retry budget for a failed Slack Web API call. */
    public static final int DEFAULT_API_MAX_RETRIES = 3;

    /** Default base delay, in milliseconds, for the API retry backoff. */
    public static final long DEFAULT_API_RETRY_BASE_MS = 500L;

    /** Default number of inbound Slack events processed at once. */
    public static final int DEFAULT_MAX_CONCURRENT_EVENTS = 32;

    /** Default number of accepted inbound events that may wait for a slot. */
    public static final int DEFAULT_MAX_QUEUED_EVENTS = 256;

    private final int requestTimeoutSeconds;
    private final int groupCompletionTimeoutSeconds;
    private final int apiMaxRetries;
    private final long apiRetryBaseMs;
    private final int maxConcurrentEvents;
    private final int maxQueuedEvents;

    @Inject
    public SlackConfig(
            @ConfigProperty(name = "eddi.slack.request-timeout-seconds",
                            defaultValue = "" + DEFAULT_REQUEST_TIMEOUT_SECONDS) int requestTimeoutSeconds,
            @ConfigProperty(name = "eddi.slack.group-completion-timeout-seconds",
                            defaultValue = "" + DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS) int groupCompletionTimeoutSeconds,
            @ConfigProperty(name = "eddi.slack.api-max-retries", defaultValue = "" + DEFAULT_API_MAX_RETRIES) int apiMaxRetries,
            @ConfigProperty(name = "eddi.slack.api-retry-base-ms", defaultValue = "" + DEFAULT_API_RETRY_BASE_MS) long apiRetryBaseMs,
            @ConfigProperty(name = "eddi.slack.max-concurrent-events",
                            defaultValue = "" + DEFAULT_MAX_CONCURRENT_EVENTS) int maxConcurrentEvents,
            @ConfigProperty(name = "eddi.slack.max-queued-events",
                            defaultValue = "" + DEFAULT_MAX_QUEUED_EVENTS) int maxQueuedEvents) {

        // A non-positive timeout would make every Slack turn fail instantly, and a
        // negative retry budget would skip the first attempt entirely. Fall back to the
        // shipped default rather than honour a value that disables the channel.
        this.requestTimeoutSeconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : DEFAULT_REQUEST_TIMEOUT_SECONDS;
        this.groupCompletionTimeoutSeconds = groupCompletionTimeoutSeconds > 0
                ? groupCompletionTimeoutSeconds
                : DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS;
        this.apiMaxRetries = apiMaxRetries >= 1 ? apiMaxRetries : DEFAULT_API_MAX_RETRIES;
        this.apiRetryBaseMs = apiRetryBaseMs >= 0 ? apiRetryBaseMs : DEFAULT_API_RETRY_BASE_MS;
        // Zero concurrency would refuse every event; a negative queue is meaningless.
        this.maxConcurrentEvents = maxConcurrentEvents > 0 ? maxConcurrentEvents : DEFAULT_MAX_CONCURRENT_EVENTS;
        this.maxQueuedEvents = maxQueuedEvents >= 0 ? maxQueuedEvents : DEFAULT_MAX_QUEUED_EVENTS;
    }

    /**
     * Constructor with the inbound-event bounds at their shipped defaults, for
     * callers written before those bounds existed.
     */
    public SlackConfig(int requestTimeoutSeconds, int groupCompletionTimeoutSeconds, int apiMaxRetries, long apiRetryBaseMs) {
        this(requestTimeoutSeconds, groupCompletionTimeoutSeconds, apiMaxRetries, apiRetryBaseMs, DEFAULT_MAX_CONCURRENT_EVENTS,
                DEFAULT_MAX_QUEUED_EVENTS);
    }

    /** Inbound events processed at once; further accepted events wait. */
    public int getMaxConcurrentEvents() {
        return maxConcurrentEvents;
    }

    /**
     * Accepted events that may wait for a processing slot. Beyond running plus
     * waiting, an event is refused with a retryable status so Slack redelivers it.
     */
    public int getMaxQueuedEvents() {
        return maxQueuedEvents;
    }

    /** Seconds a single agent turn may take before Slack is told it timed out. */
    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    /** Seconds a whole group discussion may take before it is abandoned. */
    public int getGroupCompletionTimeoutSeconds() {
        return groupCompletionTimeoutSeconds;
    }

    /** Attempts, including the first, for a Slack Web API call. */
    public int getApiMaxRetries() {
        return apiMaxRetries;
    }

    /** Base delay for the exponential backoff between API attempts. */
    public long getApiRetryBaseMs() {
        return apiRetryBaseMs;
    }
}
