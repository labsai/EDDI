/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams.rest;

import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.integrations.teams.TeamsActivityHandler;
import ai.labs.eddi.integrations.teams.TeamsMetrics;
import ai.labs.eddi.integrations.teams.TeamsTokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The Bot Connector messaging endpoint of every Teams integration:
 * {@code POST /integrations/teams/messages}, the URL an Azure Bot's "Messaging
 * endpoint" names.
 * <p>
 * Public at the HTTP layer (the Bot Connector sends no EDDI credential); every
 * request is authenticated by its Bot Connector JWT
 * ({@link TeamsTokenVerifier}) before its body is acted on, and refused with
 * 401 (403 for a key not endorsed for the channel) otherwise. A verified
 * activity is de-duplicated by (app id, conversation, activity id),
 * acknowledged with 200 at once, and processed in the background — the Bot
 * Connector expects a prompt answer and retries a delivery it saw fail.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@Path("/integrations/teams")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Integrations / Teams", description = "Microsoft Teams (Bot Connector) messaging endpoint")
@PermitAll
public class RestTeamsWebhook {

    private static final Logger LOGGER = Logger.getLogger(RestTeamsWebhook.class);

    static final Duration DEDUP_WINDOW = Duration.ofMinutes(10);

    private final TeamsTokenVerifier verifier;
    private final TeamsActivityHandler handler;
    private final ObjectMapper objectMapper;
    private final ICache<String, Boolean> dedup;
    private final int maxBodyBytes;
    private final TeamsMetrics metrics;

    @Inject
    public RestTeamsWebhook(TeamsTokenVerifier verifier, TeamsActivityHandler handler, ObjectMapper objectMapper, ICacheFactory cacheFactory,
            TeamsMetrics metrics, @ConfigProperty(name = "eddi.teams.max-body-bytes", defaultValue = "262144") int maxBodyBytes) {
        this.metrics = metrics != null ? metrics : TeamsMetrics.none();
        this.verifier = verifier;
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.dedup = cacheFactory.getCache(CacheFactory.TEAMS_ACTIVITY_DEDUP, DEDUP_WINDOW);
        this.maxBodyBytes = maxBodyBytes;
    }

    @POST
    @Path("/messages")
    @Consumes(MediaType.WILDCARD)
    public Response messages(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                             @HeaderParam(HttpHeaders.CONTENT_LENGTH) Long contentLength, InputStream body) {
        if (contentLength != null && contentLength > maxBodyBytes) {
            return Response.status(413).build();
        }
        byte[] raw;
        try {
            raw = body == null ? new byte[0] : body.readNBytes(maxBodyBytes + 1);
        } catch (IOException e) {
            return Response.status(400).build();
        }
        return messages(authorization, raw);
    }

    /** Everything after the body was read. Package-private for tests. */
    Response messages(String authorization, byte[] raw) {
        if (raw.length > maxBodyBytes) {
            return Response.status(413).build();
        }
        JsonNode activity;
        try {
            activity = objectMapper.readTree(raw);
        } catch (IOException e) {
            activity = null;
        }
        // An unparseable body still has to be authenticated first, so a probe learns
        // nothing about it; it is refused like a missing token.
        String serviceUrl = activity == null ? null : text(activity, "serviceUrl");
        String channelId = activity == null ? null : text(activity, "channelId");
        TeamsTokenVerifier.Verification verification = verifier.verify(authorization, serviceUrl, channelId, handler::isKnownApp);
        if (!verification.valid()) {
            LOGGER.warnf("Teams activity refused: %s", verification.outcome());
            metrics.refused(verification.outcome());
            int status = verification.outcome() == TeamsTokenVerifier.Outcome.ENDORSEMENT ? 403 : 401;
            return Response.status(status).build();
        }
        if (activity == null || !activity.isObject()) {
            return Response.status(400).build();
        }
        String activityId = text(activity, "id");
        String conversationId = text(activity.path("conversation"), "id");
        if (activityId != null && conversationId != null) {
            String key = verification.appId() + "|" + conversationId + "|" + activityId;
            if (dedup.putIfAbsent(key, Boolean.TRUE) != null) {
                LOGGER.debugf("Duplicate Teams activity %s — skipping", sanitize(activityId));
                metrics.activity(TeamsMetrics.DUPLICATE);
                return Response.ok().build();
            }
        }
        handler.handleAsync(verification.appId(), activity);
        return Response.ok().build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
