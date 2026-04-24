/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight Slack Web API client using {@link HttpClient}. No external SDK
 * dependency — Slack's API is just REST.
 * <p>
 * Currently supports:
 * <ul>
 * <li>{@code chat.postMessage} — post a message to a channel/thread</li>
 * </ul>
 * <p>
 * Error handling policy:
 * <ul>
 * <li><b>Retryable failures</b> (network, HTTP 429/500/503) throw
 * {@link SlackDeliveryException} so callers can implement retry logic.</li>
 * <li><b>Non-retryable API failures</b> (e.g., {@code ok:false} with
 * {@code channel_not_found}) return {@code null} and log a warning.</li>
 * </ul>
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class SlackWebApiClient {

    private static final Logger LOGGER = Logger.getLogger(SlackWebApiClient.class);
    private static final String SLACK_API_BASE = "https://slack.com/api/";

    /** HTTP status codes that are worth retrying. */
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 500, 502, 503, 504);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Inject
    public SlackWebApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    /**
     * Post a message to a Slack channel or thread.
     *
     * @param authToken
     *            the Bearer token (e.g., "Bearer xoxb-...")
     * @param channelId
     *            the Slack channel ID
     * @param threadTs
     *            thread timestamp for threaded replies (null for top-level)
     * @param text
     *            the message text (Slack mrkdwn format)
     * @return the message timestamp (ts) on success, or null for non-retryable API
     *         errors
     * @throws SlackDeliveryException
     *             on retryable failures (network, HTTP 429/500/503)
     */
    public String postMessage(String authToken, String channelId, String threadTs, String text) {
        try {
            // Build JSON body using Jackson for proper escaping (handles all
            // Unicode control characters, surrogate pairs, etc.)
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("channel", channelId);
            body.put("text", text);
            if (threadTs != null) {
                body.put("thread_ts", threadTs);
            }
            body.put("mrkdwn", true);

            String jsonBody = objectMapper.writeValueAsString(body);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(SLACK_API_BASE + "chat.postMessage"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", authToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Retryable HTTP errors — throw so caller can retry
            if (RETRYABLE_STATUS_CODES.contains(response.statusCode())) {
                throw new SlackDeliveryException(
                        "Slack API returned retryable HTTP " + response.statusCode());
            }

            // Non-retryable HTTP errors — log and return null
            if (response.statusCode() != 200) {
                LOGGER.warnf("Slack chat.postMessage returned HTTP %d: %s", response.statusCode(),
                        truncateForLog(response.body()));
                return null;
            }

            // Parse response with Jackson for robust field extraction
            JsonNode responseJson = objectMapper.readTree(response.body());

            if (!responseJson.path("ok").asBoolean(false)) {
                String error = responseJson.path("error").asText("unknown");
                LOGGER.warnf("Slack chat.postMessage returned ok=false: error=%s", error);
                return null;
            }

            // Extract "ts" — the posted message's timestamp (used for threading)
            return responseJson.has("ts") ? responseJson.get("ts").asText() : null;

        } catch (SlackDeliveryException e) {
            throw e; // re-throw — don't wrap in another exception
        } catch (java.io.IOException e) {
            throw new SlackDeliveryException("Network error calling Slack API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SlackDeliveryException("Slack API call interrupted", e);
        } catch (Exception e) {
            // Unexpected errors (e.g., JSON serialization) are deterministic — retrying
            // won't help. Log and return null (non-retryable), consistent with API errors.
            LOGGER.errorf(e, "Unexpected non-retryable error calling Slack API: %s", e.getMessage());
            return null;
        }
    }

    private static String truncateForLog(String text) {
        if (text == null)
            return "";
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
