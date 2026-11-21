/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import ai.labs.eddi.engine.httpclient.outbound.RequestBody;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.integrations.channels.ChannelConnectionCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Sends messages through the Bot Connector REST API, over {@link OutboundHttp}.
 * <ul>
 * <li><b>Credential per send.</b> The token comes from the integration's
 * connection through {@link ChannelConnectionCredentials#resolve} with the
 * activity's {@code serviceUrl} as the target, so the connection's
 * {@code baseUrlAllowlist} decides where it may go, and nothing is cached
 * here.</li>
 * <li><b>Pacing.</b> Teams allows a bot 7 messages a second, 8 in 2 seconds, 60
 * in 30 seconds and 1800 an hour in one conversation. A send waits for a slot
 * when the wait is at most {@link #MAX_PACING_WAIT}; beyond that (the hourly
 * cap) it is refused.</li>
 * <li><b>Retries.</b> 429, 412, 502 and 504 are retried with exponential
 * backoff and jitter (2 s to 20 s, at most three retries), honouring a
 * {@code Retry-After} up to 20 s.</li>
 * <li><b>Size.</b> Text longer than {@link #MAX_CHUNK_CHARS} UTF-16 units
 * (about 80 KB, the size Teams guarantees to deliver) is split into several
 * messages, at a line break where there is one.</li>
 * <li>Messages are sent with {@code textFormat: extendedmarkdown}.</li>
 * </ul>
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamsConnectorClient {

    private static final Logger LOGGER = Logger.getLogger(TeamsConnectorClient.class);

    static final int MAX_CHUNK_CHARS = 40_000;
    static final Duration MAX_PACING_WAIT = Duration.ofSeconds(30);
    static final int MAX_RETRIES = 3;
    static final long MIN_BACKOFF_MS = 2_000;
    static final long MAX_BACKOFF_MS = 20_000;
    static final Set<Integer> RETRYABLE = Set.of(429, 412, 502, 504);
    static final long MAX_RESPONSE_BYTES = 64L * 1024;

    /** Per-conversation windows: {window length ms, max sends}. */
    static final long[][] WINDOWS = {{1_000, 7}, {2_000, 8}, {30_000, 60}, {3_600_000, 1_800}};

    /** Waits; a seam for tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final OutboundHttp http;
    private final ChannelConnectionCredentials credentials;
    private final ObjectMapper objectMapper;
    private final LongSupplier clockMillis;
    private final Sleeper sleeper;
    private final Cache<String, Deque<Long>> sendTimes = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(50_000)
            .build();

    @Inject
    public TeamsConnectorClient(OutboundHttp http, ChannelConnectionCredentials credentials, ObjectMapper objectMapper) {
        this(http, credentials, objectMapper, System::currentTimeMillis, Thread::sleep);
    }

    TeamsConnectorClient(OutboundHttp http, ChannelConnectionCredentials credentials, ObjectMapper objectMapper, LongSupplier clockMillis,
            Sleeper sleeper) {
        this.http = http;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
        this.clockMillis = clockMillis;
        this.sleeper = sleeper;
    }

    /**
     * Sends {@code text} into a conversation, as a reply to {@code replyToId} when
     * given, split into as many messages as needed.
     *
     * @return the id of the last message sent, or {@code null} when the service
     *         returned none
     * @throws TeamsDeliveryException
     *             when a message could not be delivered
     */
    public String sendText(ChannelIntegrationConfiguration integration, String serviceUrl, String conversationId, String replyToId,
                           String text) {
        String lastId = null;
        for (String chunk : chunk(text)) {
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("type", "message");
            activity.put("text", chunk);
            activity.put("textFormat", "extendedmarkdown");
            lastId = send(integration, serviceUrl, conversationId, replyToId, activity);
        }
        return lastId;
    }

    /**
     * Sends one activity. Package-private and general so later tiers (cards, typing
     * indicators) reuse the pacing and retries.
     */
    String send(ChannelIntegrationConfiguration integration, String serviceUrl, String conversationId, String replyToId,
                Map<String, Object> activity) {
        URI target = activityUri(serviceUrl, conversationId, replyToId);
        String body;
        try {
            body = objectMapper.writeValueAsString(activity);
        } catch (IOException e) {
            throw new TeamsDeliveryException("Could not serialize the activity", e);
        }
        String integrationName = integration == null ? null : integration.getName();
        pace(paceKey(integration, conversationId));
        for (int attempt = 0;; attempt++) {
            ResolvedCredential credential;
            try {
                credential = credentials.resolve(integration, target);
            } catch (ConnectionException e) {
                throw new TeamsDeliveryException("The connection of Teams integration '" + integrationName + "' could not be resolved: "
                        + e.getMessage(), e);
            }
            OutboundResponse<String> response;
            try {
                var request = OutboundRequest.builder(Profile.DEFAULT, target)
                        .ssrf(SsrfPolicy.STRICT)
                        .redirects(RedirectPolicy.NEVER)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header(credential.headerName(), credential.headerValue())
                        .method("POST", new RequestBody.StringBody(body, StandardCharsets.UTF_8))
                        .build();
                response = http.send(request, BodyMode.string(MAX_RESPONSE_BYTES, BodyMode.OnOverflow.TRUNCATE));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TeamsDeliveryException("Interrupted while sending to Teams", e);
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    backoff(attempt, null);
                    continue;
                }
                throw new TeamsDeliveryException("Network error sending to Teams: " + e.getMessage(), e);
            }
            int status = response.status();
            if (status >= 200 && status < 300) {
                return idOf(response.body());
            }
            if (RETRYABLE.contains(status) && attempt < MAX_RETRIES) {
                backoff(attempt, response.firstHeader("Retry-After").orElse(null));
                continue;
            }
            LOGGER.warnf("Teams integration '%s': the Bot Connector answered HTTP %d", integrationName, status);
            throw new TeamsDeliveryException("The Bot Connector answered HTTP " + status);
        }
    }

    /** {@code {serviceUrl}v3/conversations/{id}/activities[/{replyToId}]}. */
    static URI activityUri(String serviceUrl, String conversationId, String replyToId) {
        if (serviceUrl == null || conversationId == null || conversationId.isBlank()) {
            throw new TeamsDeliveryException("No serviceUrl or conversation id to send to");
        }
        URI base;
        try {
            base = URI.create(serviceUrl);
        } catch (IllegalArgumentException e) {
            throw new TeamsDeliveryException("Malformed serviceUrl");
        }
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null) {
            throw new TeamsDeliveryException("The serviceUrl must be a plain https URL");
        }
        String prefix = serviceUrl.endsWith("/") ? serviceUrl : serviceUrl + "/";
        String path = prefix + "v3/conversations/" + encode(conversationId) + "/activities";
        if (replyToId != null && !replyToId.isBlank()) {
            path += "/" + encode(replyToId);
        }
        return URI.create(path);
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String paceKey(ChannelIntegrationConfiguration integration, String conversationId) {
        String appId = integration == null || integration.getPlatformConfig() == null
                ? ""
                : String.valueOf(integration.getPlatformConfig().get(TeamsChannelType.KEY_APP_ID));
        return appId + "|" + conversationId;
    }

    /** Waits until every window has room for one more send, then records it. */
    void pace(String key) {
        Deque<Long> times = sendTimes.get(key, k -> new ArrayDeque<>());
        while (true) {
            long wait;
            synchronized (times) {
                long now = clockMillis.getAsLong();
                while (!times.isEmpty() && times.peekFirst() <= now - WINDOWS[WINDOWS.length - 1][0]) {
                    times.pollFirst();
                }
                wait = 0;
                for (long[] window : WINDOWS) {
                    wait = Math.max(wait, waitFor(times, now, window[0], (int) window[1]));
                }
                if (wait == 0) {
                    times.addLast(now);
                    return;
                }
            }
            if (wait > MAX_PACING_WAIT.toMillis()) {
                throw new TeamsDeliveryException("The Teams per-conversation rate limit is reached; try again later");
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TeamsDeliveryException("Interrupted while pacing Teams sends", e);
            }
        }
    }

    /**
     * How long until a window of {@code length} ms holds fewer than {@code max}
     * sends; 0 when it already does.
     */
    private static long waitFor(Deque<Long> times, long now, long length, int max) {
        int inWindow = 0;
        Long oldestInWindow = null;
        var iterator = times.descendingIterator();
        while (iterator.hasNext()) {
            long time = iterator.next();
            if (time <= now - length) {
                break;
            }
            inWindow++;
            oldestInWindow = time;
            if (inWindow >= max) {
                break;
            }
        }
        if (inWindow < max) {
            return 0;
        }
        return oldestInWindow + length - now + 1;
    }

    private void backoff(int attempt, String retryAfter) {
        long delay = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS << attempt);
        if (retryAfter != null) {
            try {
                delay = Math.min(MAX_BACKOFF_MS, Math.max(0, Long.parseLong(retryAfter.trim()) * 1000));
            } catch (NumberFormatException ignored) {
                // An HTTP date: keep the computed backoff.
            }
        }
        long jitter = (long) (delay * 0.2 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        try {
            sleeper.sleep(Math.max(0, delay + jitter));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TeamsDeliveryException("Interrupted while waiting to retry a Teams send", e);
        }
    }

    private String idOf(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode id = node.get("id");
            return id != null && id.isTextual() ? id.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Splits text into chunks of at most {@link #MAX_CHUNK_CHARS} UTF-16 units,
     * preferring a line break in the second half of a chunk and never splitting a
     * surrogate pair. Blank text gives no chunk.
     */
    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int start = 0;
        while (text.length() - start > MAX_CHUNK_CHARS) {
            int end = start + MAX_CHUNK_CHARS;
            int newline = text.lastIndexOf('\n', end - 1);
            if (newline > start + MAX_CHUNK_CHARS / 2) {
                end = newline + 1;
            } else if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        chunks.add(text.substring(start));
        return chunks;
    }
}
