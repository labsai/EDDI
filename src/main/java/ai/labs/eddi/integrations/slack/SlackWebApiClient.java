package ai.labs.eddi.integrations.slack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight Slack Web API client using {@link HttpClient}. No external SDK
 * dependency — Slack's API is just REST.
 * <p>
 * Currently supports:
 * <ul>
 * <li>{@code chat.postMessage} — post a message to a channel/thread</li>
 * </ul>
 * <p>
 * Additional methods (reactions, files, etc.) can be added as needed.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class SlackWebApiClient {

    private static final Logger LOGGER = Logger.getLogger(SlackWebApiClient.class);
    private static final String SLACK_API_BASE = "https://slack.com/api/";

    private final HttpClient httpClient;

    @Inject
    public SlackWebApiClient() {
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
     */
    public void postMessage(String authToken, String channelId, String threadTs, String text) {
        try {
            // Build JSON body manually to avoid Jackson dependency for this simple case
            var json = new StringBuilder("{");
            json.append("\"channel\":\"").append(escapeJson(channelId)).append("\"");
            json.append(",\"text\":\"").append(escapeJson(text)).append("\"");
            if (threadTs != null) {
                json.append(",\"thread_ts\":\"").append(escapeJson(threadTs)).append("\"");
            }
            // Use mrkdwn formatting
            json.append(",\"mrkdwn\":true");
            json.append("}");

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(SLACK_API_BASE + "chat.postMessage"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", authToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warnf("Slack chat.postMessage returned HTTP %d: %s", response.statusCode(),
                        response.body().substring(0, Math.min(200, response.body().length())));
            } else if (!response.body().contains("\"ok\":true")) {
                LOGGER.warnf("Slack chat.postMessage returned ok=false: %s",
                        response.body().substring(0, Math.min(200, response.body().length())));
            }
        } catch (Exception e) {
            LOGGER.errorf("Failed to call Slack chat.postMessage: %s", e.getMessage());
        }
    }

    /**
     * Minimal JSON string escaping for safe embedding in JSON values.
     */
    static String escapeJson(String value) {
        if (value == null)
            return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
