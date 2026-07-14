/*
 * Copyright EDDI contributors
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight Slack Web API client using {@link HttpClient}. No external SDK
 * dependency — Slack's API is just REST.
 * <p>
 * Currently supports:
 * <ul>
 * <li>{@code chat.postMessage} — post a plain-text message to a
 * channel/thread</li>
 * <li>{@code chat.postMessage} with Block Kit blocks — interactive messages
 * (HITL approval notifications with Approve/Reject buttons)</li>
 * <li>{@code chat.update} — replace an existing message's text/blocks (used to
 * finalize an approval message once a reviewer decides)</li>
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
        // Convert standard Markdown to Slack mrkdwn format
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channelId);
        body.put("text", convertMarkdownToSlackMrkdwn(text));
        if (threadTs != null) {
            body.put("thread_ts", threadTs);
        }
        body.put("mrkdwn", true);

        JsonNode responseJson = callSlackApi("chat.postMessage", authToken, body);
        if (responseJson == null) {
            return null;
        }
        // Extract "ts" — the posted message's timestamp (used for threading)
        return responseJson.has("ts") ? responseJson.get("ts").asText() : null;
    }

    /**
     * Post an interactive Block Kit message to a Slack channel or thread. Used for
     * HITL approval notifications (Approve/Reject buttons).
     * <p>
     * {@code blocks} is the raw Slack blocks array (as a {@code List} of block
     * maps). {@code fallbackText} is the plain-text accessibility fallback shown in
     * notifications and by clients that cannot render blocks.
     *
     * @return the posted message's ts on success, or {@code null} on non-retryable
     *         API errors
     * @throws SlackDeliveryException
     *             on retryable failures (network, HTTP 429/500/503)
     */
    public String postBlocksMessage(String authToken, String channelId, String threadTs,
                                    List<?> blocks, String fallbackText) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channelId);
        // text acts as the notification/accessibility fallback for block messages
        body.put("text", fallbackText != null ? fallbackText : "");
        body.put("blocks", blocks);
        if (threadTs != null) {
            body.put("thread_ts", threadTs);
        }

        JsonNode responseJson = callSlackApi("chat.postMessage", authToken, body);
        if (responseJson == null) {
            return null;
        }
        return responseJson.has("ts") ? responseJson.get("ts").asText() : null;
    }

    /**
     * Update an existing message via {@code chat.update} — replaces its text and
     * (optionally) blocks. Used to finalize a HITL approval message once a reviewer
     * decides (e.g. remove the buttons and show "Approved by …").
     *
     * @param blocks
     *            new blocks, or {@code null} to clear blocks and show plain text
     * @return {@code true} if the update succeeded, {@code false} on non-retryable
     *         API errors
     * @throws SlackDeliveryException
     *             on retryable failures (network, HTTP 429/500/503)
     */
    public boolean updateMessage(String authToken, String channelId, String ts,
                                 String text, List<?> blocks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channelId);
        body.put("ts", ts);
        body.put("text", convertMarkdownToSlackMrkdwn(text));
        // Explicitly send an empty blocks array to remove existing blocks (buttons)
        body.put("blocks", blocks != null ? blocks : List.of());

        return callSlackApi("chat.update", authToken, body) != null;
    }

    /**
     * Send a JSON POST to a Slack Web API method and parse the response.
     * Centralizes retry classification and {@code ok:false} handling shared by
     * {@code chat.postMessage} and {@code chat.update}.
     *
     * @return the parsed response JSON on success ({@code ok:true}), or
     *         {@code null} on any non-retryable failure (HTTP != 200, {@code
     *         ok:false}, or an unexpected deterministic error)
     * @throws SlackDeliveryException
     *             on retryable failures (network, HTTP 429/500/503)
     */
    private JsonNode callSlackApi(String endpoint, String authToken, Map<String, Object> body) {
        try {
            String jsonBody = objectMapper.writeValueAsString(body);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(SLACK_API_BASE + endpoint))
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
                LOGGER.warnf("Slack %s returned HTTP %d: %s", endpoint, response.statusCode(),
                        truncateForLog(response.body()));
                return null;
            }

            // Parse response with Jackson for robust field extraction
            JsonNode responseJson = objectMapper.readTree(response.body());

            if (!responseJson.path("ok").asBoolean(false)) {
                String error = responseJson.path("error").asText("unknown");
                LOGGER.warnf("Slack %s returned ok=false: error=%s", endpoint, error);
                return null;
            }

            return responseJson;

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

    /**
     * Convert standard Markdown to Slack mrkdwn format.
     * <p>
     * Key differences handled:
     * <ul>
     * <li>{@code **bold**} → {@code *bold*}</li>
     * <li>{@code # Heading} → {@code *Heading*} (bold, no heading support)</li>
     * <li>{@code ~~strike~~} → {@code ~strike~}</li>
     * <li>Markdown tables → code blocks (Slack has no table support)</li>
     * <li>Horizontal rules ({@code ---}) → Unicode line</li>
     * </ul>
     * Code blocks (``` fenced) are preserved untouched.
     */
    static String convertMarkdownToSlackMrkdwn(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String[] lines = text.split("\n", -1);
        var result = new StringBuilder();
        boolean inCodeBlock = false;
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Toggle code block state — preserve code blocks untouched
            if (line.trim().startsWith("```")) {
                if (inTable) {
                    // Close the table-as-code-block wrapper before the real code block
                    result.append("```\n");
                    inTable = false;
                }
                inCodeBlock = !inCodeBlock;
                result.append(line).append("\n");
                continue;
            }

            if (inCodeBlock) {
                result.append(line).append("\n");
                continue;
            }

            // Detect markdown table rows (| col | col |)
            if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                // Skip separator rows (|---|---|)
                if (line.matches("^\\s*\\|[-:\\s|]+\\|\\s*$")) {
                    continue;
                }
                if (!inTable) {
                    inTable = true;
                    result.append("```\n");
                }
                // Clean up the table row for monospace display
                String cleaned = line.replaceAll("\\*\\*(.+?)\\*\\*", "$1"); // remove bold in tables
                result.append(cleaned).append("\n");
                continue;
            } else if (inTable) {
                // End of table
                inTable = false;
                result.append("```\n");
            }

            // Convert headings: # Heading → *Heading*
            if (line.matches("^#{1,6}\\s+.*")) {
                line = line.replaceFirst("^#{1,6}\\s+", "");
                line = "*" + line.trim() + "*";
            }

            // Convert bold: **text** → *text*
            line = line.replaceAll("\\*\\*(.+?)\\*\\*", "*$1*");

            // Convert strikethrough: ~~text~~ → ~text~
            line = line.replaceAll("~~(.+?)~~", "~$1~");

            // Convert horizontal rules
            if (line.matches("^\\s*[-*_]{3,}\\s*$")) {
                line = "───────────────────────────";
            }

            result.append(line).append("\n");
        }

        // Close any dangling table block
        if (inTable) {
            result.append("```\n");
        }

        // Remove trailing newline
        if (!result.isEmpty() && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }

        return result.toString();
    }

    private static String truncateForLog(String text) {
        if (text == null)
            return "";
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
