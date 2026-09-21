/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Slack channel's timeouts and retry budget were compile-time constants
 * while the OpenAI-compatible adapter treated the identical concerns as
 * configuration.
 * <p>
 * The asymmetry mattered most for the turn timeout. An agent whose turn
 * legitimately takes longer than a minute — a multi-step tool-calling turn, a
 * slow provider, a cascade escalation — always failed on Slack and worked over
 * {@code /v1}, where the operator can raise the limit. There was no way to tune
 * it short of a rebuild, and no log line named the sixty-second boundary as the
 * cause.
 * <p>
 * Two properties follow from that. The defaults must equal the constants they
 * replace, so an operator who sets nothing sees no change; and a value that
 * would disable the channel must not be honoured, because a typo in a timeout
 * should not take Slack down.
 */
@DisplayName("slack configuration")
class SlackConfigTest {

    private static SlackConfig config(int requestTimeout, int groupTimeout, int retries, long retryBase) {
        return new SlackConfig(requestTimeout, groupTimeout, retries, retryBase);
    }

    private static SlackConfig defaults() {
        return config(SlackConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS, SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS,
                SlackConfig.DEFAULT_API_MAX_RETRIES, SlackConfig.DEFAULT_API_RETRY_BASE_MS);
    }

    @Test
    @DisplayName("the defaults are the constants they replaced")
    void defaultsMatchTheFormerConstants() {
        var c = defaults();
        assertEquals(60, c.getRequestTimeoutSeconds(), "the turn timeout was a hard-coded 60 seconds");
        assertEquals(300, c.getGroupCompletionTimeoutSeconds(), "the group-discussion wait was a hard-coded 300 seconds");
        assertEquals(3, c.getApiMaxRetries(), "the Slack API retry budget was a hard-coded 3");
        assertEquals(500L, c.getApiRetryBaseMs(), "the backoff base was a hard-coded 500ms");
    }

    @Test
    @DisplayName("configured values are used as given")
    void configuredValuesWin() {
        var c = config(240, 900, 5, 250L);
        assertEquals(240, c.getRequestTimeoutSeconds());
        assertEquals(900, c.getGroupCompletionTimeoutSeconds());
        assertEquals(5, c.getApiMaxRetries());
        assertEquals(250L, c.getApiRetryBaseMs());
    }

    /**
     * A zero or negative timeout makes every Slack turn fail instantly. That is
     * worse than ignoring the operator's value, so it is ignored — the same
     * fail-safe direction the A2A handler takes with its own timeout.
     */
    @Test
    @DisplayName("a non-positive timeout falls back rather than taking the channel down")
    void nonPositiveTimeoutsFallBack() {
        for (int bad : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertEquals(SlackConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS, config(bad, 300, 3, 500L).getRequestTimeoutSeconds(),
                    "a request timeout of " + bad + " would fail every turn instantly");
            assertEquals(SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS, config(60, bad, 3, 500L).getGroupCompletionTimeoutSeconds(),
                    "a group timeout of " + bad + " would abandon every discussion instantly");
        }
    }

    /**
     * The retry loop runs {@code for (attempt = 1; attempt <= maxRetries; …)}, so a
     * budget below one skips the first attempt entirely and no Slack API call is
     * ever made.
     */
    @Test
    @DisplayName("a retry budget below one falls back, because zero attempts means no call at all")
    void retryBudgetBelowOneFallsBack() {
        assertEquals(SlackConfig.DEFAULT_API_MAX_RETRIES, config(60, 300, 0, 500L).getApiMaxRetries());
        assertEquals(SlackConfig.DEFAULT_API_MAX_RETRIES, config(60, 300, -3, 500L).getApiMaxRetries());
    }

    /**
     * Zero is a legitimate backoff — retry immediately — so only a negative value
     * falls back.
     */
    @Test
    @DisplayName("a zero backoff is honoured; a negative one falls back")
    void backoffBoundary() {
        assertEquals(0L, config(60, 300, 3, 0L).getApiRetryBaseMs(), "retrying with no delay is a choice, not a mistake");
        assertEquals(SlackConfig.DEFAULT_API_RETRY_BASE_MS, config(60, 300, 3, -1L).getApiRetryBaseMs());
    }

    /** A single attempt means "do not retry", which is a legitimate policy. */
    @Test
    @DisplayName("a single attempt is honoured")
    void singleAttemptIsHonoured() {
        assertEquals(1, config(60, 300, 1, 500L).getApiMaxRetries());
    }
}
