/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.rest;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.slack.SlackEventHandler;
import ai.labs.eddi.integrations.slack.SlackInteractivityHandler;
import ai.labs.eddi.integrations.slack.SlackSignatureVerifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Signing secrets are resolved from {@link ChannelTargetRouter}. Verification
 * is two-stage: the pooled set of all known secrets first rejects anything no
 * configured app signed, then — once the event's channel has been read — the
 * signature must match the secret of the integration that OWNS that channel. A
 * holder of one integration's secret therefore cannot drive another
 * integration's channel. An event whose channel nobody owns (a DM) is routed to
 * the integration whose secret actually signed it.
 * <p>
 * Critical: Slack expects HTTP 200 within 3 seconds. This endpoint responds
 * immediately and processes events asynchronously.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@Path("/integrations/slack")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Integrations / Slack Webhook", description = "Slack Events API webhook receiver")
public class RestSlackWebhook {

    private static final Logger LOGGER = Logger.getLogger(RestSlackWebhook.class);
    private static final String CHANNEL_TYPE_SLACK = "slack";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChannelTargetRouter channelTargetRouter;
    private final SlackSignatureVerifier signatureVerifier;
    private final SlackEventHandler eventHandler;
    private final SlackInteractivityHandler interactivityHandler;
    private final ObjectMapper objectMapper;

    @Inject
    public RestSlackWebhook(ChannelTargetRouter channelTargetRouter,
            SlackSignatureVerifier signatureVerifier,
            SlackEventHandler eventHandler,
            SlackInteractivityHandler interactivityHandler,
            ObjectMapper objectMapper) {
        this.channelTargetRouter = channelTargetRouter;
        this.signatureVerifier = signatureVerifier;
        this.eventHandler = eventHandler;
        this.interactivityHandler = interactivityHandler;
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

        // Step 1: Verify signature against all known signing secrets
        Set<String> signingSecrets = channelTargetRouter.getSigningSecrets("slack");
        if (!signatureVerifier.verify(timestamp, rawBody, signature, signingSecrets)) {
            LOGGER.warnf("Slack signature verification failed (timestamp=%s)", sanitize(timestamp));
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
                    LOGGER.debugf("Slack event received: type=%s, event_id=%s", sanitize(eventType), sanitize(eventId));

                    // Step 3a: bind the signature to the integration that owns the
                    // routed channel (or, for an unowned channel, find which one
                    // signed it).
                    var origin = verifyOrigin(event, rawBody, signature, timestamp);
                    if (origin == null) {
                        return Response.status(Response.Status.FORBIDDEN)
                                .entity("{\"error\":\"Invalid signature\"}")
                                .build();
                    }

                    // Delegate to handler (async — returns immediately)
                    // Pin the workspace to the SIGNING integration's configured teamId
                    // where it has one, so a validly-signed event cannot name a
                    // different workspace's namespace (Finding B). Fall back to the
                    // payload team_id only when no teamId is configured.
                    String teamId = origin.pinnedTeamId() != null
                            ? origin.pinnedTeamId()
                            : stringOrNull(payload.get("team_id"));
                    eventHandler.handleEventAsync(eventId, event, botUserId(payload),
                            new SlackEventHandler.EventOrigin(teamId, origin.signingIntegrationName()));
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

    /**
     * What the second verification stage established about an event.
     *
     * @param signingIntegrationName
     *            for an event in a channel nobody owns, the integration whose
     *            secret signed it ({@code null} for a legacy connector, or when the
     *            channel is owned and routing goes by channel)
     * @param pinnedTeamId
     *            the workspace the signing/owning integration declares
     *            ({@code platformConfig.teamId}), or {@code null} when it declares
     *            none — the caller then falls back to the payload {@code team_id}
     */
    private record VerifiedOrigin(String signingIntegrationName, String pinnedTeamId) {
    }

    /**
     * Second verification stage, after the pooled check: bind the signature to the
     * event's channel.
     * <ul>
     * <li>A channel owned by an integration (or legacy connector) must be signed
     * with THAT owner's secret. An owner with no secret cannot be verified and is
     * refused — never re-admitted through the pool.</li>
     * <li>A channel nobody owns — a DM, whose D-prefixed id is never configured —
     * is attributed to the integration whose secret signed it, and the handler
     * routes the DM to that integration's default target.</li>
     * </ul>
     *
     * @return the verified origin, or {@code null} to reject with 403
     */
    private VerifiedOrigin verifyOrigin(Map<String, Object> event, String rawBody, String signature,
                                        String timestamp) {
        String channel = stringOrNull(event.get("channel"));
        if (channel == null) {
            // Nothing channel-bound to act on: the handler drops events without a
            // channel, so the pooled check is all there is to bind.
            return new VerifiedOrigin(null, null);
        }
        if (channelTargetRouter.isChannelOwned(CHANNEL_TYPE_SLACK, channel)) {
            var ownerSecret = channelTargetRouter.getSigningSecretForChannel(CHANNEL_TYPE_SLACK, channel);
            if (ownerSecret.isEmpty()
                    || !signatureVerifier.verifyWithSecret(timestamp, rawBody, signature, ownerSecret.get())) {
                LOGGER.warnf("Slack event for channel %s was not signed by the integration that owns it — rejecting",
                        sanitize(channel));
                return null;
            }
            return new VerifiedOrigin(null,
                    configuredTeamId(channelTargetRouter.getIntegration(CHANNEL_TYPE_SLACK, channel).orElse(null)));
        }
        for (var identity : channelTargetRouter.getSigningIdentities(CHANNEL_TYPE_SLACK)) {
            if (signatureVerifier.verifyWithSecret(timestamp, rawBody, signature, identity.signingSecret())) {
                var config = channelTargetRouter.getIntegrationByName(CHANNEL_TYPE_SLACK, identity.integrationName())
                        .orElse(null);
                return new VerifiedOrigin(identity.integrationName(), configuredTeamId(config));
            }
        }
        // The pooled check passed a moment ago, so this is a refresh racing the
        // request. Refuse rather than guess.
        LOGGER.warnf("Slack event for unowned channel %s matched no signing identity — rejecting", sanitize(channel));
        return null;
    }

    /**
     * The integration's declared workspace id, or {@code null} when it declares
     * none.
     */
    private static String configuredTeamId(ChannelIntegrationConfiguration config) {
        if (config == null || config.getPlatformConfig() == null) {
            return null;
        }
        String teamId = config.getPlatformConfig().get("teamId");
        return teamId != null && !teamId.isBlank() ? teamId : null;
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * Receive Slack interactivity payloads (block_actions from HITL Approve/Reject
     * buttons). Slack sends {@code application/x-www-form-urlencoded} with a single
     * {@code payload} parameter containing a JSON string.
     * <p>
     * Unlike {@code /events} (which may verify against the pooled set of signing
     * secrets), a HITL decision is bound to the integration that OWNS it: we
     * resolve the owning integration from the button value first, then verify the
     * RAW body against ONLY that integration's signing secret. This prevents a
     * holder of any one Slack secret from forging an approval on another
     * integration's paused conversation (cross-integration IDOR). Legacy per-agent
     * ChannelConnector secrets are excluded — a decision that cannot be bound to a
     * new-style integration is rejected.
     * <p>
     * Responds 200 within Slack's 3-second window and processes the decision
     * asynchronously.
     *
     * @param rawBody
     *            the raw request body (needed for signature verification)
     * @param signature
     *            the X-Slack-Signature header
     * @param timestamp
     *            the X-Slack-Request-Timestamp header
     * @return 200 OK for valid requests; 403 for invalid/unbindable signatures; 400
     *         for a missing/malformed payload
     */
    @POST
    @Path("/interactive")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response handleInteractive(String rawBody,
                                      @HeaderParam("X-Slack-Signature") String signature,
                                      @HeaderParam("X-Slack-Request-Timestamp") String timestamp) {

        // Step 1: Extract the "payload" form parameter (URL-encoded JSON string).
        String payloadJson = extractPayloadParam(rawBody);
        if (payloadJson == null || payloadJson.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Missing payload\"}")
                    .build();
        }

        // Step 2: Resolve the owning integration's signing secret from the payload
        // (button value carries the integration name). A null secret means the
        // decision cannot be bound to a new-style integration (legacy/unknown) —
        // reject rather than accepting a pooled secret.
        String owningSecret = interactivityHandler.resolveSigningSecretForDecision(payloadJson);
        if (owningSecret == null) {
            LOGGER.warn("Slack interactive decision could not be bound to an owning integration — rejecting");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\":\"Invalid signature\"}")
                    .build();
        }

        // Step 3: Verify the RAW body against ONLY the owning integration's secret.
        if (!signatureVerifier.verifyWithSecret(timestamp, rawBody, signature, owningSecret)) {
            LOGGER.warnf("Slack interactive signature verification failed (timestamp=%s)", sanitize(timestamp));
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\":\"Invalid signature\"}")
                    .build();
        }

        // Step 4: Process async (Slack's 3-second requirement) and ack immediately.
        interactivityHandler.handlePayloadAsync(payloadJson);
        return Response.ok().build();
    }

    /**
     * Extract and URL-decode the {@code payload} parameter from an
     * {@code x-www-form-urlencoded} body. Returns {@code null} if absent.
     */
    static String extractPayloadParam(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        for (String pair : rawBody.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            if ("payload".equals(key)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    // malformed percent-encoding — treat as a bad payload (handleInteractive
                    // maps null -> 400) rather than letting it become a 500. This runs
                    // before signature verification, so fail as a client error.
                    return null;
                }
            }
        }
        return null;
    }
    /**
     * This app's own Slack user id, from the event envelope.
     *
     * Slack puts it on {@code authorizations[].user_id} for the entry whose
     * {@code is_bot} is true, on every {@code event_callback}. Taking it from here
     * rather than calling {@code auth.test} means no extra request, no cache to
     * invalidate on reinstall, and a value that is correct per workspace in a
     * multi-workspace install.
     *
     * {@code null} when the envelope does not carry one — an older payload shape,
     * or a user-token authorization. Callers must degrade rather than depend on it.
     */
    private static String botUserId(Map<String, Object> payload) {
        Object authorizations = payload.get("authorizations");
        if (!(authorizations instanceof List<?> list)) {
            return null;
        }
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> auth
                    && Boolean.TRUE.equals(auth.get("is_bot"))
                    && auth.get("user_id") instanceof String userId
                    && !userId.isBlank()) {
                return userId;
            }
        }
        return null;
    }

}
