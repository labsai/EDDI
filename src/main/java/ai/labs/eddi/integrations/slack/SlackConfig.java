/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

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

    private final int requestTimeoutSeconds;
    private final int groupCompletionTimeoutSeconds;
    private final int apiMaxRetries;
    private final long apiRetryBaseMs;
    private final boolean namespaceUserIds;
    private final String legacyTeamId;

    /**
     * The four tunables, with bare Slack user ids — the shipped default.
     */
    public SlackConfig(int requestTimeoutSeconds, int groupCompletionTimeoutSeconds, int apiMaxRetries, long apiRetryBaseMs) {
        this(requestTimeoutSeconds, groupCompletionTimeoutSeconds, apiMaxRetries, apiRetryBaseMs, false, Optional.empty());
    }

    /**
     * The four tunables plus the namespacing switch, with no configured legacy
     * team.
     */
    public SlackConfig(int requestTimeoutSeconds, int groupCompletionTimeoutSeconds, int apiMaxRetries, long apiRetryBaseMs,
            boolean namespaceUserIds) {
        this(requestTimeoutSeconds, groupCompletionTimeoutSeconds, apiMaxRetries, apiRetryBaseMs, namespaceUserIds, Optional.empty());
    }

    @Inject
    public SlackConfig(
            @ConfigProperty(name = "eddi.slack.request-timeout-seconds",
                            defaultValue = "" + DEFAULT_REQUEST_TIMEOUT_SECONDS) int requestTimeoutSeconds,
            @ConfigProperty(name = "eddi.slack.group-completion-timeout-seconds",
                            defaultValue = "" + DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS) int groupCompletionTimeoutSeconds,
            @ConfigProperty(name = "eddi.slack.api-max-retries", defaultValue = "" + DEFAULT_API_MAX_RETRIES) int apiMaxRetries,
            @ConfigProperty(name = "eddi.slack.api-retry-base-ms", defaultValue = "" + DEFAULT_API_RETRY_BASE_MS) long apiRetryBaseMs,
            @ConfigProperty(name = "eddi.slack.namespace-user-ids", defaultValue = "false") boolean namespaceUserIds,
            @ConfigProperty(name = "eddi.slack.legacy-team-id") Optional<String> legacyTeamId) {
        this.namespaceUserIds = namespaceUserIds;
        this.legacyTeamId = legacyTeamId != null ? legacyTeamId.map(String::trim).filter(s -> !s.isEmpty()).orElse(null) : null;

        // A non-positive timeout would make every Slack turn fail instantly, and a
        // negative retry budget would skip the first attempt entirely. Fall back to the
        // shipped default rather than honour a value that disables the channel.
        this.requestTimeoutSeconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : DEFAULT_REQUEST_TIMEOUT_SECONDS;
        this.groupCompletionTimeoutSeconds = groupCompletionTimeoutSeconds > 0
                ? groupCompletionTimeoutSeconds
                : DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS;
        this.apiMaxRetries = apiMaxRetries >= 1 ? apiMaxRetries : DEFAULT_API_MAX_RETRIES;
        this.apiRetryBaseMs = apiRetryBaseMs >= 0 ? apiRetryBaseMs : DEFAULT_API_RETRY_BASE_MS;
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

    /**
     * Whether a Slack user is identified in EDDI as {@code slack:<teamId>:<userId>}
     * rather than by the bare Slack id. Slack ids are unique only within a
     * workspace, so with several workspaces on one deployment the bare id can name
     * two different people — who would then share conversations and long-term
     * memory.
     * <p>
     * Off by default: switching an existing deployment over changes every Slack
     * user's EDDI id, which templates ({@code {userInfo.userId}}), long-term memory
     * and GDPR requests are keyed by. A multi-workspace deployment opts in; see
     * {@link #getLegacyTeamId()} for carrying existing memories over.
     */
    public boolean isNamespaceUserIds() {
        return namespaceUserIds;
    }

    /**
     * The workspace that bare Slack ids stored before namespacing belonged to, or
     * {@code null}. When set (or derivable because every routed integration pins
     * the same {@code teamId} and no legacy Slack connector is routed — derived
     * from the current configuration only), a user of that workspace keeps their
     * bare-id threads and has their bare-id long-term memories copied to the
     * namespaced id on first contact. Without it nothing is aliased: in a
     * multi-workspace deployment a bare id may already mix two people's data.
     */
    public String getLegacyTeamId() {
        return legacyTeamId;
    }
}
