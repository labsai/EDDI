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
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

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

    private final int requestTimeoutSeconds;
    private final int groupCompletionTimeoutSeconds;
    private final int apiMaxRetries;
    private final long apiRetryBaseMs;

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

    // ─── The Slack Web API destination and late answers ───
    //
    // Kept below the constructor and its getters, and set through fields and
    // package-private setters rather than more constructors, so that the many
    // callers that build a SlackConfig directly keep the one shape they use.

    private static final Logger LOGGER = Logger.getLogger(SlackConfig.class);

    /** Where the Slack Web API lives. */
    public static final String DEFAULT_API_BASE_URL = "https://slack.com/api/";

    /**
     * Default seconds a turn that outlived {@code request-timeout-seconds} may keep
     * running and still have its answer posted to the thread.
     */
    public static final int DEFAULT_LATE_ANSWER_WINDOW_SECONDS = 900;

    /**
     * {@code eddi.slack.api-base-url}: the one destination of every Slack Web API
     * call and therefore of every bot token. The client sends to it and the channel
     * router judges a vaulted token's {@code allowedHosts} against it — both read
     * {@link #getApiBaseUrl()}, so the host that is checked is the host that is
     * called. Validated when the bean is created ({@link #checkApiBaseUrl}), which
     * {@link Startup} makes part of the application's startup.
     */
    @ConfigProperty(name = "eddi.slack.api-base-url", defaultValue = DEFAULT_API_BASE_URL)
    String apiBaseUrl = DEFAULT_API_BASE_URL;

    /**
     * {@code eddi.slack.late-answer-window-seconds}: how long after the first wait
     * gave up a turn may still complete and have its answer posted; 0 turns late
     * answers off.
     */
    @ConfigProperty(name = "eddi.slack.late-answer-window-seconds", defaultValue = "" + DEFAULT_LATE_ANSWER_WINDOW_SECONDS)
    int lateAnswerWindowSeconds = DEFAULT_LATE_ANSWER_WINDOW_SECONDS;

    /**
     * Fails the startup on a bad {@code eddi.slack.api-base-url}, and settles the
     * late-answer window. A base URL other than Slack's own is logged once by host:
     * every bot token of every integration goes there.
     */
    @PostConstruct
    void checkApiBaseUrl() {
        this.apiBaseUrl = validateApiBaseUrl(apiBaseUrl);
        if (lateAnswerWindowSeconds < 0) {
            lateAnswerWindowSeconds = DEFAULT_LATE_ANSWER_WINDOW_SECONDS;
        }
        if (!DEFAULT_API_BASE_URL.equals(apiBaseUrl)) {
            // The host only: the value was checked to carry no credentials, but a
            // log line is no place to find out it did.
            URI uri = URI.create(apiBaseUrl);
            LOGGER.warnf("eddi.slack.api-base-url points the Slack Web API at host %s: every Slack bot token is sent "
                    + "there", uri.getHost());
            if (uri.getRawPath() == null || uri.getRawPath().equals("/")) {
                LOGGER.warnf("eddi.slack.api-base-url has no path; the Slack Web API lives under /api/, so a method "
                        + "name appended to the bare host is answered 404 unless the host serves it there");
            }
        }
    }

    /**
     * Test seam: the API base URL, validated like the property.
     *
     * @throws IllegalArgumentException
     *             if {@code url} is not an acceptable base URL
     */
    SlackConfig withApiBaseUrl(String url) {
        this.apiBaseUrl = validateApiBaseUrl(url);
        return this;
    }

    /**
     * Test seam: the late-answer window; negative falls back to the default.
     */
    SlackConfig withLateAnswerWindowSeconds(int seconds) {
        this.lateAnswerWindowSeconds = seconds >= 0 ? seconds : DEFAULT_LATE_ANSWER_WINDOW_SECONDS;
        return this;
    }

    /**
     * Seconds, counted from the moment the wait for a turn gave up
     * ({@link #getRequestTimeoutSeconds}), during which the turn may still complete
     * and have its answer — or a failure notice — posted to the thread. After that
     * the thread is told to ask again. 0 turns late answers off: the thread is told
     * to ask again at once, as before.
     */
    public int getLateAnswerWindowSeconds() {
        return lateAnswerWindowSeconds;
    }

    /**
     * The base URL of the Slack Web API, ending in a slash; a method name is
     * appended to it. {@code https://slack.com/api/} unless
     * {@code eddi.slack.api-base-url} says otherwise. Every bot token is sent here.
     */
    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    /**
     * Checks an API base URL and returns it with a trailing slash. Every bot token
     * is sent to this host, so it must be {@code https} — or {@code http} to a
     * loopback address, which is how a test double on the same machine is reached —
     * and carry no credentials, query or fragment, which would travel with every
     * call or be dropped by it. A literal loopback address only ({@code localhost},
     * {@code 127.0.0.0/8}, {@code [::1]}, parsed by the JDK): a host name that
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
        if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65_535)) {
            return "has a port outside 1-65535";
        }
        if (scheme.equals("https") || (scheme.equals("http") && isLiteralLoopback(host))) {
            return null;
        }
        return "must use https (plain http is accepted only for a loopback address)";
    }

    /**
     * {@code localhost}, or an IP literal the JDK parses as a loopback address. No
     * name resolution: {@link InetAddress#ofLiteral} only reads a literal, so
     * {@code 127.0.0.1.example} and {@code 127.999.999.999} are refused.
     */
    static boolean isLiteralLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.ofLiteral(literal).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
