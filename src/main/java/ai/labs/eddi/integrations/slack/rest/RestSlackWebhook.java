package ai.labs.eddi.integrations.slack.rest;

import ai.labs.eddi.integrations.slack.SlackEventHandler;
import ai.labs.eddi.integrations.slack.SlackIntegrationConfig;
import ai.labs.eddi.integrations.slack.SlackSignatureVerifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * JAX-RS webhook endpoint for Slack Events API.
 * <p>
 * Handles:
 * <ul>
 * <li><b>URL Verification</b> — Slack sends a challenge on app setup; we echo
 * it back.</li>
 * <li><b>Event Callbacks</b> — {@code app_mention} and {@code message} events
 * are delegated to {@link SlackEventHandler} for async processing.</li>
 * </ul>
 * <p>
 * Critical: Slack expects HTTP 200 within 3 seconds. This endpoint responds
 * immediately and processes events asynchronously.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@Path("/integrations/slack")
@Produces(MediaType.APPLICATION_JSON)
public class RestSlackWebhook {

    private static final Logger LOGGER = Logger.getLogger(RestSlackWebhook.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SlackIntegrationConfig config;
    private final SlackSignatureVerifier signatureVerifier;
    private final SlackEventHandler eventHandler;
    private final ObjectMapper objectMapper;

    @Inject
    public RestSlackWebhook(SlackIntegrationConfig config,
            SlackSignatureVerifier signatureVerifier,
            SlackEventHandler eventHandler,
            ObjectMapper objectMapper) {
        this.config = config;
        this.signatureVerifier = signatureVerifier;
        this.eventHandler = eventHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * Receive Slack Events API webhooks.
     *
     * @param rawBody
     *            the raw request body (needed for signature verification)
     * @param signature
     *            the X-Slack-Signature header
     * @param timestamp
     *            the X-Slack-Request-Timestamp header
     * @return 200 OK (immediately) for valid requests; 403 for invalid signatures
     */
    @POST
    @Path("/events")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response handleEvents(String rawBody,
                                 @HeaderParam("X-Slack-Signature") String signature,
                                 @HeaderParam("X-Slack-Request-Timestamp") String timestamp) {

        if (!config.enabled()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Slack integration is not enabled\"}")
                    .build();
        }

        // Step 1: Verify signature
        if (!signatureVerifier.verify(timestamp, rawBody, signature)) {
            LOGGER.warnf("Slack signature verification failed (timestamp=%s)", timestamp);
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\":\"Invalid signature\"}")
                    .build();
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(rawBody, MAP_TYPE);
            String type = (String) payload.get("type");

            // Step 2: URL Verification (Slack sends this once during app setup)
            if ("url_verification".equals(type)) {
                String challenge = (String) payload.get("challenge");
                LOGGER.infof("Slack URL verification challenge received");
                return Response.ok()
                        .type(MediaType.APPLICATION_JSON)
                        .entity(objectMapper.writeValueAsString(Map.of("challenge", challenge != null ? challenge : "")))
                        .build();
            }

            // Step 3: Event callbacks
            if ("event_callback".equals(type)) {
                String eventId = (String) payload.get("event_id");
                @SuppressWarnings("unchecked")
                Map<String, Object> event = (Map<String, Object>) payload.get("event");

                if (event != null) {
                    String eventType = (String) event.get("type");
                    LOGGER.debugf("Slack event received: type=%s, event_id=%s", eventType, eventId);

                    // Delegate to handler (async — returns immediately)
                    eventHandler.handleEventAsync(eventId, eventType, event);
                }
            }

            // Always respond 200 immediately (Slack's 3-second requirement)
            return Response.ok().build();

        } catch (Exception e) {
            LOGGER.errorf("Failed to parse Slack event payload: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid payload\"}")
                    .build();
        }
    }
}
