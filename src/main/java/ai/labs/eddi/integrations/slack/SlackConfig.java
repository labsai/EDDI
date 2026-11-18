/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

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
@Startup
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

    /** Where the Slack Web API lives. */
    public static final String DEFAULT_API_BASE_URL = "https://slack.com/api/";

    /**
     * A literal loopback host: {@code localhost}, {@code 127.x.x.x} or
     * {@code [::1]}.
     */
    private static final Pattern LOOPBACK_HOST = Pattern.compile("(?i)localhost|127(\\.\\d{1,3}){3}|\\[::1]");

    private final int requestTimeoutSeconds;
    private final int groupCompletionTimeoutSeconds;
    private final int apiMaxRetries;
    private final long apiRetryBaseMs;

    /**
     * {@code eddi.slack.api-base-url}. Injected into the field rather than the
     * constructor so that the many callers that build a {@code SlackConfig}
     * directly keep their signature; {@link #checkApiBaseUrl} validates it when the
     * bean is created, which {@link Startup} makes part of the application's
     * startup.
     */
    @ConfigProperty(name = "eddi.slack.api-base-url", defaultValue = DEFAULT_API_BASE_URL)
    String apiBaseUrl = DEFAULT_API_BASE_URL;

    @Inject
    public SlackConfig(
            @ConfigProperty(name = "eddi.slack.request-timeout-seconds",
                            defaultValue = "" + DEFAULT_REQUEST_TIMEOUT_SECONDS) int requestTimeoutSeconds,
            @ConfigProperty(name = "eddi.slack.group-completion-timeout-seconds",
                            defaultValue = "" + DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS) int groupCompletionTimeoutSeconds,
            @ConfigProperty(name = "eddi.slack.api-max-retries", defaultValue = "" + DEFAULT_API_MAX_RETRIES) int apiMaxRetries,
            @ConfigProperty(name = "eddi.slack.api-retry-base-ms", defaultValue = "" + DEFAULT_API_RETRY_BASE_MS) long apiRetryBaseMs) {

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

    /**
     * As the injected constructor, with the API base URL given — what a test uses
     * to point the Slack client at a fake. The URL is validated like the property.
     *
     * @throws IllegalArgumentException
     *             if {@code apiBaseUrl} is not an acceptable base URL
     */
    public SlackConfig(int requestTimeoutSeconds, int groupCompletionTimeoutSeconds, int apiMaxRetries, long apiRetryBaseMs,
            String apiBaseUrl) {
        this(requestTimeoutSeconds, groupCompletionTimeoutSeconds, apiMaxRetries, apiRetryBaseMs);
        this.apiBaseUrl = validateApiBaseUrl(apiBaseUrl);
    }

    /** Fails the startup on a bad {@code eddi.slack.api-base-url}. */
    @PostConstruct
    void checkApiBaseUrl() {
        this.apiBaseUrl = validateApiBaseUrl(apiBaseUrl);
    }

    /**
     * The base URL of the Slack Web API, ending in a slash; a method name is
     * appended to it. {@code https://slack.com/api/} unless
     * {@code eddi.slack.api-base-url} says otherwise.
     */
    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    /**
     * Checks an API base URL and returns it with a trailing slash. Every bot token
     * is sent to this host, so it must be {@code https} — or {@code http} to a
     * loopback address, which is how a test double on the same machine is reached —
     * and carry no credentials, query or fragment, which would travel with every
     * call or be dropped by it. A literal loopback address only: a host name that
     * merely resolves there is not trusted.
     *
     * @throws IllegalArgumentException
     *             naming the property, never echoing the value (it may hold a
     *             password someone pasted)
     */
    static String validateApiBaseUrl(String value) {
        String url = value == null ? "" : value.trim();
        String problem = apiBaseUrlProblem(url);
        if (problem != null) {
            throw new IllegalArgumentException("eddi.slack.api-base-url " + problem);
        }
        return url.endsWith("/") ? url : url + "/";
    }

    private static String apiBaseUrlProblem(String url) {
        if (url.isEmpty()) {
            return "must not be empty";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "is not a valid URL";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "must be an absolute http(s) URL with a host";
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "must not contain credentials, a query or a fragment";
        }
        boolean loopback = LOOPBACK_HOST.matcher(host).matches();
        if (scheme.equals("https") || (scheme.equals("http") && loopback)) {
            return null;
        }
        return "must use https (plain http is accepted only for a loopback address)";
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
